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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Minimal HTTP/1.1 server for exposing Conduit diagnostics.
 *
 * <p>Two representations of the same counters are served:
 * <ul>
 *   <li>the JSON path ({@code metrics.http-path}, default {@code /metrics}) for dashboards and
 *       ad-hoc scripts;</li>
 *   <li>the Prometheus path ({@code metrics.prometheus-path}, default
 *       {@code /metrics/prometheus}) in the text exposition format, so the endpoint can be scraped
 *       directly instead of through a bespoke exporter.</li>
 * </ul>
 *
 * <h3>Robustness</h3>
 * Requests are handled on a small worker pool and every socket carries a read timeout, so a client
 * that connects and then says nothing — deliberately or because it died mid-request — cannot wedge
 * the accept loop and take the endpoint down with it. Request headers are bounded in both count and
 * length for the same reason.
 *
 * <p>When {@code metrics.auth-token} is set, a request must present it as
 * {@code Authorization: Bearer <token>} or it is refused with 401. That makes binding the endpoint
 * somewhere other than loopback a deliberate, defensible choice rather than an open door.
 */
public final class ConduitMetricsServer implements AutoCloseable {

  private static final Logger logger = LogManager.getLogger(ConduitMetricsServer.class);

  /** How long a client may leave the socket idle before it is dropped. */
  private static final int SOCKET_TIMEOUT_MS = 5000;

  /** Upper bound on request header lines read before the request is refused. */
  private static final int MAX_HEADER_LINES = 64;

  /** Upper bound on a single request line or header line, in characters. */
  private static final int MAX_LINE_LENGTH = 4096;

  private static final int WORKER_THREADS = 2;

  private final ConduitDiagnostics diagnostics;
  private final String jsonPath;
  private final String prometheusPath;
  private final String authToken;
  private final ServerSocket serverSocket;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile ExecutorService workers;
  private Thread thread;

  /**
   * Opens the listening socket. Nothing is served until {@link #start()} is called.
   *
   * @param host           the bind address
   * @param port           the bind port
   * @param jsonPath       the path serving the JSON snapshot
   * @param prometheusPath the path serving the Prometheus text exposition
   * @param authToken      a bearer token required on every request, or blank for no authentication
   * @param diagnostics    the counter registry to render
   */
  public ConduitMetricsServer(String host, int port, String jsonPath, String prometheusPath,
      String authToken, ConduitDiagnostics diagnostics) throws IOException {
    this.diagnostics = diagnostics;
    this.jsonPath = jsonPath;
    this.prometheusPath = prometheusPath;
    this.authToken = authToken == null ? "" : authToken.trim();
    this.serverSocket = new ServerSocket(port, 50, InetAddress.getByName(host));
  }

  /** Starts the background accept loop. */
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    workers = Executors.newFixedThreadPool(WORKER_THREADS, r -> {
      Thread t = new Thread(r, "conduit-metrics-worker");
      t.setDaemon(true);
      return t;
    });
    thread = new Thread(this::serveLoop, "conduit-metrics-http");
    thread.setDaemon(true);
    thread.start();
    logger.info("[Conduit] Metrics endpoint listening on {}:{} ({} json, {} prometheus{}).",
        serverSocket.getInetAddress().getHostAddress(), serverSocket.getLocalPort(),
        jsonPath, prometheusPath, authToken.isEmpty() ? "" : ", token required");
  }

  /** Returns the bound port, useful when port 0 is used in tests. */
  public int getPort() {
    return serverSocket.getLocalPort();
  }

  private void serveLoop() {
    while (running.get()) {
      try {
        Socket socket = serverSocket.accept();
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        ExecutorService pool = workers;
        if (pool == null) {
          socket.close();
          continue;
        }
        try {
          pool.execute(() -> handle(socket));
        } catch (RejectedExecutionException shuttingDown) {
          socket.close();
        }
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
      String requestLine = readLine(in);
      if (requestLine == null) {
        return;
      }
      boolean authorized = authToken.isEmpty();
      int headerLines = 0;
      while (true) {
        String line = readLine(in);
        if (line == null || line.isEmpty()) {
          break;
        }
        if (++headerLines > MAX_HEADER_LINES) {
          writeResponse(out, 431, "Request Header Fields Too Large",
              "{\"error\":\"too_many_headers\"}");
          return;
        }
        if (!authorized && isBearerHeader(line)) {
          authorized = true;
        }
      }
      if (!authorized) {
        writeResponse(out, 401, "Unauthorized", "{\"error\":\"unauthorized\"}");
        return;
      }
      if (matches(requestLine, jsonPath)) {
        writeResponse(out, 200, "OK", ConduitMetricsSnapshot.from(diagnostics).toJson());
      } else if (matches(requestLine, prometheusPath)) {
        writeResponse(out, 200, "OK", ConduitMetricsSnapshot.from(diagnostics).toPrometheus(),
            "text/plain; version=0.0.4; charset=utf-8");
      } else {
        writeResponse(out, 404, "Not Found", "{\"error\":\"not_found\"}");
      }
    } catch (SocketTimeoutException idle) {
      logger.debug("[Conduit] Metrics request timed out before a complete request arrived.");
    } catch (IOException e) {
      logger.debug("[Conduit] Metrics request failed: {}", e.getMessage());
    }
  }

  private boolean isBearerHeader(String line) {
    int colon = line.indexOf(':');
    if (colon < 0 || !line.substring(0, colon).trim()
        .toLowerCase(Locale.ROOT).equals("authorization")) {
      return false;
    }
    String value = line.substring(colon + 1).trim();
    if (value.length() != authToken.length() + "Bearer ".length()
        || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return false;
    }
    // Constant-time comparison: the token is a shared secret, so do not leak its prefix length.
    return constantTimeEquals(value.substring(7), authToken);
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) {
      return false;
    }
    int diff = 0;
    for (int i = 0; i < a.length(); i++) {
      diff |= a.charAt(i) ^ b.charAt(i);
    }
    return diff == 0;
  }

  /** Matches {@code GET <path>} with either a trailing space (HTTP/1.x) or end of line. */
  private static boolean matches(String requestLine, String path) {
    if (path == null || path.isEmpty()) {
      return false;
    }
    String prefix = "GET " + path;
    return requestLine.equals(prefix) || requestLine.startsWith(prefix + " ")
        || requestLine.startsWith(prefix + "?");
  }

  /** Reads one line, refusing any that exceeds {@link #MAX_LINE_LENGTH}. */
  private static String readLine(BufferedReader in) throws IOException {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') {
        int end = sb.length();
        if (end > 0 && sb.charAt(end - 1) == '\r') {
          sb.setLength(end - 1);
        }
        return sb.toString();
      }
      if (sb.length() >= MAX_LINE_LENGTH) {
        throw new IOException("request line exceeds " + MAX_LINE_LENGTH + " characters");
      }
      sb.append((char) c);
    }
    return sb.length() == 0 ? null : sb.toString();
  }

  private void writeResponse(OutputStream out, int status, String reason, String body)
      throws IOException {
    writeResponse(out, status, reason, body, "application/json");
  }

  private void writeResponse(OutputStream out, int status, String reason, String body,
      String contentType) throws IOException {
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
        + "Content-Type: " + contentType + "\r\n"
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
    ExecutorService pool = workers;
    if (pool != null) {
      pool.shutdownNow();
      try {
        pool.awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      workers = null;
    }
  }
}
