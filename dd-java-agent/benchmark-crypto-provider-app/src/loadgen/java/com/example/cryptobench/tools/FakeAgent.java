package com.example.cryptobench.tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class FakeAgent {

  private static final byte[] INFO =
      ("{\"version\":\"7.99.0\",\"endpoints\":[\"/v0.4/traces\",\"/v0.5/traces\"],"
              + "\"client_drop_p0s\":false,\"span_meta_structs\":false}")
          .getBytes(StandardCharsets.UTF_8);
  private static final byte[] TRACES_OK =
      "{\"rate_by_service\":{\"service:,env:\":1}}".getBytes(StandardCharsets.UTF_8);
  private static final AtomicLong TRACE_PAYLOADS = new AtomicLong();
  private static final byte[] OK = "{}".getBytes(StandardCharsets.UTF_8);

  public static void main(String[] args) throws IOException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 18126;
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
    server.setExecutor(Executors.newFixedThreadPool(2));
    server.createContext("/", FakeAgent::handle);
    server.start();
    System.out.println("fake agent listening on " + port);
  }

  private static void handle(HttpExchange ex) throws IOException {
    try (InputStream in = ex.getRequestBody()) {
      byte[] buf = new byte[16384];
      while (in.read(buf) >= 0) {
      }
    }
    String path = ex.getRequestURI().getPath();
    byte[] body;
    if ("/info".equals(path)) {
      body = INFO;
    } else if (path.endsWith("/traces")) {
      TRACE_PAYLOADS.incrementAndGet();
      body = TRACES_OK;
    } else if ("/bench/trace-payloads".equals(path)) {
      body = Long.toString(TRACE_PAYLOADS.get()).getBytes(StandardCharsets.UTF_8);
    } else {
      body = OK;
    }
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(200, body.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(body);
    }
  }
}
