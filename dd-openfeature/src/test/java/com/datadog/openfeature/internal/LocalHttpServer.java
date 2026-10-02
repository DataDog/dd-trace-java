package com.datadog.openfeature.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.GZIPOutputStream;

/** A local HTTP server replaying scripted responses and recording the received requests. */
public final class LocalHttpServer implements AutoCloseable {
  private final HttpServer server;
  private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final BlockingQueue<Request> received = new LinkedBlockingQueue<>();
  private volatile Response fallback = new Response(404, null, Map.of(), false);

  public LocalHttpServer() throws IOException {
    this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    this.server.createContext("/", this::handle);
    this.server.start();
  }

  /**
   * @param path the request path, with an optional query.
   * @return the URI of the given path on this server.
   */
  public URI uri(final String path) {
    return URI.create(
        "http://"
            + this.server.getAddress().getHostString()
            + ":"
            + this.server.getAddress().getPort()
            + path);
  }

  /** Queues a response. */
  public LocalHttpServer enqueue(final int status, final String body, final String... headers) {
    this.responses.add(response(status, body, false, headers));
    return this;
  }

  /** Queues a gzip encoded response. */
  public LocalHttpServer enqueueGzip(final int status, final String body, final String... headers) {
    this.responses.add(response(status, body, true, headers));
    return this;
  }

  /** Sets the response returned once queued responses are exhausted. */
  public LocalHttpServer otherwise(final int status, final String body, final String... headers) {
    this.fallback = response(status, body, false, headers);
    return this;
  }

  public List<Request> requests() {
    return this.requests;
  }

  public BlockingQueue<Request> received() {
    return this.received;
  }

  @Override
  public void close() {
    this.server.stop(0);
  }

  private static Response response(
      final int status, final String body, final boolean gzip, final String... headers) {
    final Map<String, String> headerMap = new java.util.HashMap<>();
    for (int i = 0; i < headers.length; i += 2) {
      headerMap.put(headers[i], headers[i + 1]);
    }
    return new Response(status, body, headerMap, gzip);
  }

  private void handle(final HttpExchange exchange) throws IOException {
    final Request request =
        new Request(
            exchange.getRequestMethod(),
            exchange.getRequestURI(),
            exchange.getRequestHeaders(),
            exchange.getRequestBody().readAllBytes());
    this.requests.add(request);
    this.received.add(request);
    Response response = this.responses.poll();
    if (response == null) {
      response = this.fallback;
    }
    for (final Map.Entry<String, String> header : response.headers.entrySet()) {
      exchange.getResponseHeaders().add(header.getKey(), header.getValue());
    }
    byte[] body = response.body == null ? new byte[0] : response.body.getBytes(UTF_8);
    if (response.gzip) {
      exchange.getResponseHeaders().add("Content-Encoding", "gzip");
      final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
        gzip.write(body);
      }
      body = compressed.toByteArray();
    }
    exchange.sendResponseHeaders(response.status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    }
    exchange.close();
  }

  /** A received request. */
  public static final class Request {
    public final String method;
    public final URI uri;
    public final Headers headers;
    public final byte[] body;

    Request(final String method, final URI uri, final Headers headers, final byte[] body) {
      this.method = method;
      this.uri = uri;
      this.headers = headers;
      this.body = body;
    }

    public String header(final String name) {
      return this.headers.getFirst(name);
    }

    public String bodyAsString() {
      return new String(this.body, UTF_8);
    }
  }

  private static final class Response {
    final int status;
    final String body;
    final Map<String, String> headers;
    final boolean gzip;

    Response(
        final int status,
        final String body,
        final Map<String, String> headers,
        final boolean gzip) {
      this.status = status;
      this.body = body;
      this.headers = headers;
      this.gzip = gzip;
    }
  }
}
