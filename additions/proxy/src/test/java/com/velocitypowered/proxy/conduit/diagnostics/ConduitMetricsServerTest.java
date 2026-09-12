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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConduitMetricsServerTest {

  private static ConduitMetricsServer server(String token) throws IOException {
    ConduitMetricsServer server = new ConduitMetricsServer("127.0.0.1", 0, "/metrics",
        "/metrics/prometheus", token, mock(ConduitDiagnostics.class));
    server.start();
    return server;
  }

  /** Sends a raw request and returns the whole response. */
  private static String get(int port, String path, String authHeader) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      OutputStream out = socket.getOutputStream();
      StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n");
      if (authHeader != null) {
        request.append("Authorization: ").append(authHeader).append("\r\n");
      }
      request.append("\r\n");
      out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
      out.flush();
      return readAll(socket);
    }
  }

  private static String readAll(Socket socket) throws IOException {
    StringBuilder response = new StringBuilder();
    try (BufferedReader in = new BufferedReader(
        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        response.append(line).append('\n');
      }
    }
    return response.toString();
  }

  @Test
  void servesJsonAndPrometheusOnTheirOwnPaths() throws Exception {
    try (ConduitMetricsServer server = server("")) {
      String json = get(server.getPort(), "/metrics", null);
      assertTrue(json.startsWith("HTTP/1.1 200 OK"), json);
      assertTrue(json.contains("\"totalConnections\":"), json);

      String prom = get(server.getPort(), "/metrics/prometheus", null);
      assertTrue(prom.startsWith("HTTP/1.1 200 OK"), prom);
      assertTrue(prom.contains("text/plain; version=0.0.4"), prom);
      assertTrue(prom.contains("# TYPE conduit_connections_total counter"), prom);

      assertTrue(get(server.getPort(), "/nope", null).startsWith("HTTP/1.1 404"));
    }
  }

  @Test
  void requiresTheBearerTokenWhenOneIsConfigured() throws Exception {
    try (ConduitMetricsServer server = server("s3cret")) {
      assertTrue(get(server.getPort(), "/metrics", null).startsWith("HTTP/1.1 401"));
      assertTrue(get(server.getPort(), "/metrics", "Bearer wrong").startsWith("HTTP/1.1 401"));
      assertTrue(get(server.getPort(), "/metrics", "Bearer s3cret").startsWith("HTTP/1.1 200"));
    }
  }

  /**
   * The wedge this endpoint used to be vulnerable to: a client that connects and never sends a
   * request. It must not stop the endpoint answering everyone else.
   */
  @Test
  void anIdleClientDoesNotWedgeTheEndpoint() throws Exception {
    try (ConduitMetricsServer server = server("")) {
      try (Socket silent = new Socket()) {
        silent.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5000);
        // Nothing is written on `silent`; the endpoint must still answer a real request.
        String json = get(server.getPort(), "/metrics", null);
        assertTrue(json.startsWith("HTTP/1.1 200 OK"), json);
      }
    }
  }
}
