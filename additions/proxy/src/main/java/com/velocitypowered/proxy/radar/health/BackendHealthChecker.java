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

package com.velocitypowered.proxy.radar.health;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Periodically pings every registered backend server and tracks their health state.
 *
 * <p>Health checks run on a {@link ScheduledExecutorService} at a configurable interval (default
 * 10 s). Each server is tracked independently; a warning is logged when a server transitions to
 * unhealthy and an info message is logged when it recovers.
 *
 * <p>The singleton {@link #DISABLED} instance always reports every server as healthy and performs
 * no background work.
 */
public class BackendHealthChecker {

  /**
   * Sentinel instance used when health-checking is disabled.
   * All queries return {@code true} (healthy) and no scheduler is started.
   */
  public static final BackendHealthChecker DISABLED = new BackendHealthChecker(10000) {
    @Override
    public void start(ProxyServer proxy) {
      // no-op — health checking is disabled
    }

    @Override
    public boolean isHealthy(RegisteredServer server) {
      return true;
    }

    @Override
    public String getHealthSummary() {
      return "BackendHealthChecker: DISABLED";
    }

    @Override
    public void stop() {
      // no-op
    }
  };

  private static final Logger logger = LogManager.getLogger(BackendHealthChecker.class);

  private final long intervalMs;
  private final ConcurrentHashMap<String, ServerHealthState> states = new ConcurrentHashMap<>();
  private ScheduledExecutorService scheduler;

  /**
   * Constructs a health checker that pings servers every {@code intervalMs} milliseconds.
   *
   * @param intervalMs the interval between health-check rounds, in milliseconds
   */
  public BackendHealthChecker(long intervalMs) {
    this.intervalMs = intervalMs;
  }

  /**
   * Starts the background health-check scheduler against all servers registered with
   * {@code proxy}.
   *
   * @param proxy the proxy server whose registered backends will be checked
   */
  public void start(ProxyServer proxy) {
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "conduit-health-checker");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleAtFixedRate(
        () -> runChecks(proxy), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    logger.info("[Conduit] BackendHealthChecker started (interval {}ms)", intervalMs);
  }

  /**
   * Stops the background scheduler.  Safe to call if {@link #start} was never invoked.
   */
  public void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      logger.info("[Conduit] BackendHealthChecker stopped.");
    }
  }

  /**
   * Returns {@code true} if the given server is currently considered healthy.
   *
   * <p>A server that has never been checked (e.g., registered after the last round) is optimistically
   * treated as healthy.
   *
   * @param server the backend server to query
   * @return {@code true} if healthy or not yet checked
   */
  public boolean isHealthy(RegisteredServer server) {
    ServerHealthState state = states.get(server.getServerInfo().getName());
    if (state == null) {
      return true;
    }
    return state.healthy;
  }

  /**
   * Returns a human-readable summary of the health state of all known backend servers.
   *
   * @return multi-line health summary string
   */
  public String getHealthSummary() {
    if (states.isEmpty()) {
      return "BackendHealthChecker: no servers checked yet.";
    }
    StringBuilder sb = new StringBuilder("BackendHealthChecker health summary:\n");
    for (java.util.Map.Entry<String, ServerHealthState> entry : states.entrySet()) {
      ServerHealthState s = entry.getValue();
      sb.append(String.format("  %-24s  %s  failures=%d  lastChecked=%s%n",
          entry.getKey(),
          s.healthy ? "HEALTHY" : "UNHEALTHY",
          s.consecutiveFailures,
          s.lastChecked));
    }
    return sb.toString();
  }

  private void runChecks(ProxyServer proxy) {
    for (RegisteredServer server : proxy.getAllServers()) {
      String name = server.getServerInfo().getName();
      server.ping().whenComplete((ping, err) -> {
        if (err != null) {
          handleFailure(name);
        } else {
          handleSuccess(name);
        }
      });
    }
  }

  private void handleSuccess(String name) {
    ServerHealthState prev = states.put(name, new ServerHealthState(true, 0, Instant.now()));
    if (prev != null && !prev.healthy) {
      logger.info("[Conduit] Backend server '{}' has recovered and is now healthy.", name);
    }
  }

  private void handleFailure(String name) {
    states.compute(name, (k, prev) -> {
      int failures = (prev == null ? 0 : prev.consecutiveFailures) + 1;
      boolean wasHealthy = (prev == null || prev.healthy);
      ServerHealthState next = new ServerHealthState(false, failures, Instant.now());
      if (wasHealthy) {
        logger.warn("[Conduit] Backend server '{}' is UNHEALTHY (consecutive failures: {}).",
            k, failures);
      }
      return next;
    });
  }

  // ── Inner types ────────────────────────────────────────────────────────────

  /**
   * Immutable snapshot of a single backend server's health at a point in time.
   */
  private static final class ServerHealthState {

    /** Whether the server was reachable on the last check. */
    final boolean healthy;

    /** Number of consecutive failed pings since the last successful check. */
    final int consecutiveFailures;

    /** Timestamp of the last completed health check. */
    final Instant lastChecked;

    /**
     * Constructs a new health state snapshot.
     *
     * @param healthy             {@code true} if the last ping succeeded
     * @param consecutiveFailures number of back-to-back failures
     * @param lastChecked         when this state was recorded
     */
    ServerHealthState(boolean healthy, int consecutiveFailures, Instant lastChecked) {
      this.healthy = healthy;
      this.consecutiveFailures = consecutiveFailures;
      this.lastChecked = lastChecked;
    }
  }
}
