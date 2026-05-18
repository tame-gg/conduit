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

package com.velocitypowered.proxy.conduit.diagnostics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Minimal HTTP/1.1 server for exposing Conduit diagnostics as JSON. */
public final class ConduitMetricsServer implements AutoCloseable {

  private static final Logger logger = LogManager.getLogger(ConduitMetricsServer.class);

  private final ConduitDiagnostics diagnostics;
  private final String path;
  private final ServerSocket serverSocket;
  private final AtomicBoolean running = new AtomicBoolean();
  private Thread thread;

  public ConduitMetricsServer(String host, int port, String path, ConduitDiagnostics diagnostics)
      throws IOException {
    this.diagnostics = diagnostics;
    this.path = path;
    this.serverSocket = new ServerSocket(port, 50, InetAddress.getByName(host));
  }

  /** Starts the background accept loop. */
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    thread = new Thread(this::serveLoop, "conduit-metrics-http");
    thread.setDaemon(true);
    thread.start();
    logger.info("[Conduit] Metrics endpoint listening on {}:{}{}",
        serverSocket.getInetAddress().getHostAddress(), serverSocket.getLocalPort(), path);
  }

  /** Returns the bound port, useful when port 0 is used in tests. */
  public int getPort() {
    return serverSocket.getLocalPort();
  }

  private void serveLoop() {
    while (running.get()) {
      try {
        handle(serverSocket.accept());
      } catch (IOException e) {
        if (running.get()) {
          logger.warn("[Conduit] Metrics endpoint error: {}", e.getMessage());
        }
      }
    }
  }

  private void handle(Socket socket) {
    try (socket;
        BufferedReader in = new BufferedReader(new InputStreamReader(
            socket.getInputStream(), StandardCharsets.US_ASCII));
        OutputStream out = socket.getOutputStream()) {
      String requestLine = in.readLine();
      if (requestLine == null) {
        return;
      }
      while (true) {
        String line = in.readLine();
        if (line == null || line.isEmpty()) {
          break;
        }
      }
      if (!requestLine.startsWith("GET " + path + " ")) {
        writeResponse(out, 404, "Not Found", "{\"error\":\"not_found\"}");
        return;
      }
      writeResponse(out, 200, "OK", ConduitMetricsSnapshot.from(diagnostics).toJson());
    } catch (IOException e) {
      logger.debug("[Conduit] Metrics request failed: {}", e.getMessage());
    }
  }

  private void writeResponse(OutputStream out, int status, String reason, String body)
      throws IOException {
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
        + "Content-Type: application/json\r\n"
        + "Content-Length: " + bodyBytes.length + "\r\n"
        + "Connection: close\r\n"
        + "\r\n";
    out.write(headers.getBytes(StandardCharsets.US_ASCII));
    out.write(bodyBytes);
  }

  @Override
  public void close() {
    running.set(false);
    try {
      serverSocket.close();
    } catch (IOException ignored) {
      // best-effort shutdown
    }
  }
}
