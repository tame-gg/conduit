/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.conduit.health;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Periodically pings every registered backend server and tracks their health state.
 *
 * <p>Health checks run on a {@link ScheduledExecutorService} at a configurable interval (default
 * 10 s). Each server is tracked independently; a warning is logged when a server transitions to
 * unhealthy and an info message is logged when it recovers.
 *
 * <h3>Hysteresis</h3>
 * A single missed ping is not evidence that a backend is down — a garbage-collection pause longer
 * than the per-ping timeout produces one, and pulling the server out of fallback routing for it
 * (then putting it straight back on the next successful ping) makes routing flap. A server is
 * therefore only marked unhealthy after {@code failureThreshold} consecutive failed pings, and only
 * marked healthy again after {@code successThreshold} consecutive successful ones.
 *
 * <h3>Draining</h3>
 * A server can additionally be <em>drained</em> by an operator ({@code /conduit drain <server>}).
 * A drained server is still pinged and still reports its true health, but {@link #isRoutable} — the
 * question routing actually asks — returns {@code false} for it, so no new players are sent there.
 * This is the state you want during a rolling restart.
 *
 * <p>Checking can be switched off — and back on — at runtime via {@link #setEnabled(boolean)}.
 */
public class BackendHealthChecker {

  private static final Logger logger = LogManager.getLogger(BackendHealthChecker.class);

  /** Default consecutive failed pings before a backend is marked unhealthy. */
  public static final int DEFAULT_FAILURE_THRESHOLD = 3;

  /** Default consecutive successful pings before an unhealthy backend is marked healthy. */
  public static final int DEFAULT_SUCCESS_THRESHOLD = 2;

  private volatile boolean enabled;
  private volatile long intervalMs;
  private volatile int failureThreshold;
  private volatile int successThreshold;
  private final ConcurrentHashMap<String, ServerHealthState> states = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Boolean> inflight = new ConcurrentHashMap<>();
  private final Set<String> drained = ConcurrentHashMap.newKeySet();
  private volatile ScheduledExecutorService scheduler;
  private volatile ProxyServer proxy;

  /**
   * Constructs an enabled health checker with the default hysteresis thresholds.
   *
   * @param intervalMs the interval between health-check rounds, in milliseconds
   */
  public BackendHealthChecker(long intervalMs) {
    this(intervalMs, DEFAULT_FAILURE_THRESHOLD, DEFAULT_SUCCESS_THRESHOLD, true);
  }

  /**
   * Constructs a health checker in the given initial state.
   *
   * @param intervalMs       the interval between health-check rounds, in milliseconds
   * @param failureThreshold consecutive failed pings before a backend is marked unhealthy
   * @param successThreshold consecutive successful pings before it is marked healthy again
   * @param enabled          whether checking is active; a disabled checker reports every server as
   *                         healthy and runs no background work
   */
  public BackendHealthChecker(long intervalMs, int failureThreshold, int successThreshold,
      boolean enabled) {
    this.intervalMs = intervalMs;
    this.failureThreshold = Math.max(1, failureThreshold);
    this.successThreshold = Math.max(1, successThreshold);
    this.enabled = enabled;
  }

  /**
   * Starts the background health-check scheduler against all servers registered with
   * {@code proxy}. Does nothing while checking is disabled; the proxy reference is still recorded
   * so {@link #setEnabled(boolean)} can start it later.
   *
   * @param proxy the proxy server whose registered backends will be checked
   */
  public void start(ProxyServer proxy) {
    this.proxy = proxy;
    if (!enabled) {
      return;
    }
    startScheduler();
  }

  private synchronized void startScheduler() {
    if (scheduler != null || proxy == null) {
      return;
    }
    ProxyServer target = proxy;
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "conduit-health-checker");
      t.setDaemon(true);
      return t;
    });
    long interval = intervalMs;
    executor.scheduleAtFixedRate(() -> runChecks(target), interval, interval,
        TimeUnit.MILLISECONDS);
    scheduler = executor;
    logger.info("[Conduit] BackendHealthChecker started (interval {}ms, {} failures down /"
        + " {} successes up).", interval, failureThreshold, successThreshold);
  }

  /**
   * Stops the background scheduler.  Safe to call if {@link #start} was never invoked.
   */
  public synchronized void stop() {
    ScheduledExecutorService executor = scheduler;
    if (executor != null) {
      executor.shutdownNow();
      scheduler = null;
      logger.info("[Conduit] BackendHealthChecker stopped.");
    }
  }

  /** Returns whether health checking is currently active. */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Turns health checking on or off at runtime. Turning it off stops the scheduler and clears
   * recorded state, so every server reports healthy again; turning it on starts the scheduler if
   * {@link #start} has already supplied a proxy reference.
   */
  public void setEnabled(boolean enabled) {
    if (this.enabled == enabled) {
      return;
    }
    this.enabled = enabled;
    if (enabled) {
      startScheduler();
    } else {
      stop();
      states.clear();
      inflight.clear();
    }
  }

  /**
   * Applies a new check interval, restarting the scheduler when it actually changed.
   */
  public void setIntervalMs(long intervalMs) {
    if (this.intervalMs == intervalMs) {
      return;
    }
    this.intervalMs = intervalMs;
    if (enabled && scheduler != null) {
      stop();
      startScheduler();
    }
  }

  /** Returns the interval between check rounds, in milliseconds. */
  public long getIntervalMs() {
    return intervalMs;
  }

  /** Sets the consecutive failed / successful ping counts required to flip a server's state. */
  public void setThresholds(int failureThreshold, int successThreshold) {
    this.failureThreshold = Math.max(1, failureThreshold);
    this.successThreshold = Math.max(1, successThreshold);
  }

  /** Returns the consecutive failed pings required to mark a server unhealthy. */
  public int getFailureThreshold() {
    return failureThreshold;
  }

  /** Returns the consecutive successful pings required to mark a server healthy again. */
  public int getSuccessThreshold() {
    return successThreshold;
  }

  /**
   * Returns {@code true} if the given server is currently considered healthy.
   *
   * <p>A server that has never been checked (e.g., registered after the last round) is optimistically
   * treated as healthy, as is every server while checking is disabled. Draining is deliberately
   * <em>not</em> considered here — a drained server is healthy, just closed to new arrivals. Ask
   * {@link #isRoutable} when choosing where to send a player.
   *
   * @param server the backend server to query
   * @return {@code true} if healthy or not yet checked
   */
  public boolean isHealthy(RegisteredServer server) {
    if (!enabled) {
      return true;
    }
    ServerHealthState state = states.get(server.getServerInfo().getName());
    return state == null || state.healthy;
  }

  /** Returns {@code true} when {@code server} may receive new players: healthy and not drained. */
  public boolean isRoutable(RegisteredServer server) {
    return isHealthy(server) && !isDrained(server.getServerInfo().getName());
  }

  /** Returns {@code true} when the named server is currently drained. */
  public boolean isDrained(String serverName) {
    return drained.contains(serverName);
  }

  /** Returns the names of all currently drained servers. */
  public Set<String> getDrainedServers() {
    return Set.copyOf(drained);
  }

  /**
   * Marks a server as drained or undrained.
   *
   * @return {@code true} when the state actually changed
   */
  public boolean setDrained(String serverName, boolean drainedNow) {
    boolean changed = drainedNow ? drained.add(serverName) : drained.remove(serverName);
    if (changed) {
      logger.info("[Conduit] Backend server '{}' is now {}.", serverName,
          drainedNow ? "DRAINING (no new players will be routed to it)" : "accepting players again");
    }
    return changed;
  }

  /**
   * Returns a human-readable summary of the health state of all known backend servers.
   *
   * @return multi-line health summary string
   */
  public String getHealthSummary() {
    if (!enabled) {
      return "BackendHealthChecker: DISABLED";
    }
    if (states.isEmpty() && drained.isEmpty()) {
      return "BackendHealthChecker: no servers checked yet.";
    }
    StringBuilder sb = new StringBuilder("BackendHealthChecker health summary:\n");
    for (Map.Entry<String, ServerHealthState> entry : states.entrySet()) {
      ServerHealthState s = entry.getValue();
      sb.append(String.format("  %-24s  %s%s  failures=%d  lastChecked=%s%n",
          entry.getKey(),
          s.healthy ? "HEALTHY" : "UNHEALTHY",
          isDrained(entry.getKey()) ? " (DRAINING)" : "",
          s.consecutiveFailures,
          s.lastChecked));
    }
    for (String name : drained) {
      if (!states.containsKey(name)) {
        sb.append(String.format("  %-24s  %s%n", name, "DRAINING (never checked)"));
      }
    }
    return sb.toString();
  }

  /** Per-ping timeout, scaled down from the check interval so a slow backend cannot stack up. */
  private long pingTimeoutMs() {
    return Math.max(1000L, intervalMs / 2);
  }

  /** Runs one check round against every registered backend. Package-private for tests. */
  void runChecks(ProxyServer proxy) {
    if (!enabled) {
      return;
    }
    for (RegisteredServer server : proxy.getAllServers()) {
      String name = server.getServerInfo().getName();
      // Skip if a previous check is still outstanding — prevents pile-up on slow backends.
      if (inflight.putIfAbsent(name, Boolean.TRUE) != null) {
        continue;
      }
      server.ping()
          .orTimeout(pingTimeoutMs(), TimeUnit.MILLISECONDS)
          .whenComplete((ping, err) -> {
            try {
              if (err != null) {
                handleFailure(name, err);
              } else {
                handleSuccess(name);
              }
            } finally {
              inflight.remove(name);
            }
          });
    }
  }

  private void handleSuccess(String name) {
    int required = successThreshold;
    states.compute(name, (k, prev) -> {
      int successes = (prev == null ? 0 : prev.consecutiveSuccesses) + 1;
      boolean wasHealthy = prev == null || prev.healthy;
      boolean healthy = wasHealthy || successes >= required;
      if (!wasHealthy && healthy) {
        logger.info("[Conduit] Backend server '{}' has recovered and is now healthy"
            + " ({} consecutive successful pings).", k, successes);
      }
      return new ServerHealthState(healthy, 0, successes, Instant.now());
    });
  }

  private void handleFailure(String name, Throwable err) {
    int required = failureThreshold;
    states.compute(name, (k, prev) -> {
      int failures = (prev == null ? 0 : prev.consecutiveFailures) + 1;
      boolean wasHealthy = prev == null || prev.healthy;
      boolean healthy = wasHealthy && failures < required;
      String reason = (err instanceof TimeoutException)
          ? "ping timed out" : err.getClass().getSimpleName();
      if (wasHealthy && !healthy) {
        logger.warn("[Conduit] Backend server '{}' is UNHEALTHY ({}; {} consecutive failures).",
            k, reason, failures);
      } else if (wasHealthy) {
        logger.debug("[Conduit] Backend server '{}' failed a health ping ({}; {} of {} before it"
            + " is marked unhealthy).", k, reason, failures, required);
      }
      return new ServerHealthState(healthy, failures, 0, Instant.now());
    });
  }

  // ── Inner types ────────────────────────────────────────────────────────────

  /**
   * Immutable snapshot of a single backend server's health at a point in time.
   */
  private static final class ServerHealthState {

    /** Whether the server is currently considered up, after hysteresis. */
    final boolean healthy;

    /** Number of consecutive failed pings since the last successful check. */
    final int consecutiveFailures;

    /** Number of consecutive successful pings since the last failed check. */
    final int consecutiveSuccesses;

    /** Timestamp of the last completed health check. */
    final Instant lastChecked;

    ServerHealthState(boolean healthy, int consecutiveFailures, int consecutiveSuccesses,
        Instant lastChecked) {
      this.healthy = healthy;
      this.consecutiveFailures = consecutiveFailures;
      this.consecutiveSuccesses = consecutiveSuccesses;
      this.lastChecked = lastChecked;
    }
  }
}
