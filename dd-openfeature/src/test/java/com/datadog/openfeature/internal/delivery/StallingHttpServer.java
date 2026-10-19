package com.datadog.openfeature.internal.delivery;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/** A raw HTTP endpoint that stalls before headers or after the first response body byte. */
final class StallingHttpServer implements AutoCloseable {
  private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
  private final CompletableFuture<Void> received = new CompletableFuture<>();
  private final CompletableFuture<Boolean> disconnected = new CompletableFuture<>();
  private final CountDownLatch headersAllowed;
  private final Thread thread;
  private final URI uri;
  private volatile Socket connection;

  StallingHttpServer(final boolean sendHeaders) throws Exception {
    this.uri =
        new URI(
            "http",
            null,
            this.server.getInetAddress().getHostAddress(),
            this.server.getLocalPort(),
            "/",
            null,
            null);
    this.headersAllowed = new CountDownLatch(sendHeaders ? 0 : 1);
    this.thread = new Thread(this::serve, "stalling-http-server");
    this.thread.setDaemon(true);
    this.thread.start();
  }

  URI uri() {
    return this.uri;
  }

  void awaitRequest() throws Exception {
    this.received.get(5, SECONDS);
  }

  void sendHeaders() {
    this.headersAllowed.countDown();
  }

  boolean awaitDisconnect() throws Exception {
    return this.disconnected.get(5, SECONDS);
  }

  private void serve() {
    try (Socket socket = this.server.accept()) {
      this.connection = socket;
      socket.setSoTimeout(10_000);
      final BufferedReader reader =
          new BufferedReader(new InputStreamReader(socket.getInputStream(), US_ASCII));
      String line;
      while ((line = reader.readLine()) != null && !line.isEmpty()) {}
      this.received.complete(null);
      if (!this.headersAllowed.await(5, SECONDS)) {
        throw new IllegalStateException("Response headers were not released");
      }
      socket
          .getOutputStream()
          .write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx".getBytes(US_ASCII));
      socket.getOutputStream().flush();
      this.disconnected.complete(reader.read() == -1);
    } catch (final SocketException e) {
      this.received.completeExceptionally(e);
      this.disconnected.complete(true);
    } catch (final Exception e) {
      this.received.completeExceptionally(e);
      this.disconnected.completeExceptionally(e);
    }
  }

  @Override
  public void close() throws Exception {
    this.server.close();
    final Socket socket = this.connection;
    if (socket != null) {
      socket.close();
    }
    this.thread.interrupt();
    this.thread.join(SECONDS.toMillis(5));
  }
}
