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

package com.velocitypowered.proxy.conduit.shutdown;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Registers a JVM shutdown hook that gracefully transfers all connected players to a configured
 * fallback server (or disconnects them with a friendly message) before the proxy exits.
 *
 * <p>The shutdown hook waits up to {@code shutdownTimeoutMs} milliseconds for all transfers to
 * complete before allowing the JVM to proceed with shutdown.
 */
public final class GracefulShutdown {

  private static final Logger logger = LogManager.getLogger(GracefulShutdown.class);

  private final long shutdownTimeoutMs;
  private final String shutdownMessage;
  private final String fallbackServerName;

  /**
   * Constructs a {@code GracefulShutdown} handler.
   *
   * @param shutdownTimeoutMs  the maximum time in milliseconds to wait for all transfers to finish
   * @param shutdownMessage    the disconnect message shown to players when no fallback is available
   * @param fallbackServerName the name of the preferred fallback server, or {@code null} / empty
   *                           to disconnect players immediately
   */
  public GracefulShutdown(long shutdownTimeoutMs, String shutdownMessage,
      String fallbackServerName) {
    this.shutdownTimeoutMs = shutdownTimeoutMs;
    this.shutdownMessage = shutdownMessage;
    this.fallbackServerName = fallbackServerName;
  }

  /**
   * Registers the JVM shutdown hook against the given proxy.
   *
   * @param proxy the proxy server to inspect for connected players on shutdown
   */
  public void register(ProxyServer proxy) {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(proxy),
        "conduit-graceful-shutdown"));
    logger.info("[Conduit] GracefulShutdown hook registered (timeout {}ms, fallback='{}').",
        shutdownTimeoutMs, fallbackServerName);
  }

  /**
   * Executes the graceful shutdown sequence: transfers or disconnects all connected players and
   * waits up to the configured timeout for completion.
   *
   * @param proxy the proxy server
   */
  public void shutdown(ProxyServer proxy) {
    Collection<Player> players = proxy.getAllPlayers();
    if (players.isEmpty()) {
      logger.info("[Conduit] GracefulShutdown: no players connected; exiting immediately.");
      return;
    }

    logger.info("[Conduit] GracefulShutdown: transferring {} player(s) before shutdown...",
        players.size());

    Optional<RegisteredServer> fallback = resolveFallback(proxy);
    CountDownLatch latch = new CountDownLatch(players.size());
    AtomicInteger transferred = new AtomicInteger(0);
    AtomicInteger disconnected = new AtomicInteger(0);

    for (Player player : players) {
      if (fallback.isPresent()) {
        player.createConnectionRequest(fallback.get()).fireAndForget();
        transferred.incrementAndGet();
        latch.countDown();
      } else {
        player.disconnect(Component.text(shutdownMessage));
        disconnected.incrementAndGet();
        latch.countDown();
      }
    }

    try {
      boolean finished = latch.await(shutdownTimeoutMs, TimeUnit.MILLISECONDS);
      if (finished) {
        logger.info("[Conduit] GracefulShutdown: complete — {} transferred, {} disconnected.",
            transferred.get(), disconnected.get());
      } else {
        logger.warn("[Conduit] GracefulShutdown: timed out after {}ms ({} transferred,"
            + " {} disconnected).", shutdownTimeoutMs, transferred.get(), disconnected.get());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.warn("[Conduit] GracefulShutdown: interrupted during wait.");
    }
  }

  private Optional<RegisteredServer> resolveFallback(ProxyServer proxy) {
    if (fallbackServerName == null || fallbackServerName.isBlank()) {
      return Optional.empty();
    }
    return proxy.getServer(fallbackServerName);
  }
}
