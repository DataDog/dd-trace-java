package datadog.communication.http.client;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class MockProxy implements Closeable {
  private final ServerSocket serverSocket;
  private final CountDownLatch connectLatch = new CountDownLatch(1);
  private final AtomicReference<String> connectLine = new AtomicReference<String>();
  private final AtomicReference<Map<String, String>> connectHeaders =
      new AtomicReference<Map<String, String>>(Collections.<String, String>emptyMap());
  private volatile boolean running = true;
  private final boolean requireAuth;

  public MockProxy() throws IOException {
    this(false);
  }

  public MockProxy(boolean requireAuth) throws IOException {
    this.requireAuth = requireAuth;
    serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
  }

  public int port() {
    return serverSocket.getLocalPort();
  }

  public void start() {
    Thread acceptThread =
        new Thread(
            () -> {
              while (running) {
                try {
                  Socket clientSocket = serverSocket.accept();
                  handleClient(clientSocket);
                } catch (IOException ignored) {
                  return;
                }
              }
            },
            "proxy-tunnel-server");
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  private void handleClient(Socket clientSocket) {
    Thread handler =
        new Thread(
            () -> {
              try (Socket client = clientSocket) {
                BufferedReader reader =
                    new BufferedReader(
                        new InputStreamReader(
                            client.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream clientOut = client.getOutputStream();

                String firstLine = reader.readLine();
                connectLine.set(firstLine);
                connectLatch.countDown();

                if (firstLine == null || !firstLine.startsWith("CONNECT ")) {
                  handleForwardProxyRequest(firstLine, reader, clientOut);
                  return;
                }

                Map<String, String> headers = new LinkedHashMap<String, String>();
                while (true) {
                  String line = reader.readLine();
                  if (line == null || line.isEmpty()) {
                    break;
                  }
                  int idx = line.indexOf(':');
                  if (idx > 0) {
                    headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                  }
                }
                connectHeaders.set(headers);
                if (requireAuth && connectHeaderFrom(headers, "Proxy-Authorization") == null) {
                  clientOut.write(
                      "HTTP/1.1 407 Proxy Authentication Required\r\n"
                          .getBytes(StandardCharsets.US_ASCII));
                  clientOut.write(
                      "Proxy-Authenticate: Basic realm=\"test\"\r\n\r\n"
                          .getBytes(StandardCharsets.US_ASCII));
                  clientOut.flush();
                  return;
                }

                String authority = firstLine.split(" ")[1];
                String[] hostPort = authority.split(":");
                try (Socket upstream = new Socket(hostPort[0], Integer.parseInt(hostPort[1]))) {
                  clientOut.write(
                      "HTTP/1.1 200 Connection established\r\nProxy-Agent: test\r\n\r\n"
                          .getBytes(StandardCharsets.US_ASCII));
                  clientOut.flush();

                  Thread upstreamToClient =
                      new Thread(
                          () -> transfer(upstream, client), "proxy-upstream-to-client-thread");
                  upstreamToClient.setDaemon(true);
                  upstreamToClient.start();
                  transfer(client, upstream);
                }
              } catch (Exception ignored) {
              }
            },
            "proxy-client-handler");
    handler.setDaemon(true);
    handler.start();
  }

  private void handleForwardProxyRequest(
      String firstLine, BufferedReader reader, OutputStream clientOut) throws IOException {
    Map<String, String> headers = new LinkedHashMap<String, String>();
    while (true) {
      String line = reader.readLine();
      if (line == null || line.isEmpty()) {
        break;
      }
      int idx = line.indexOf(':');
      if (idx > 0) {
        headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
      }
    }
    connectHeaders.set(headers);
    if (requireAuth && connectHeaderFrom(headers, "Proxy-Authorization") == null) {
      clientOut.write(
          "HTTP/1.1 407 Proxy Authentication Required\r\n".getBytes(StandardCharsets.US_ASCII));
      clientOut.write(
          "Proxy-Authenticate: Basic realm=\"test\"\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      clientOut.flush();
      return;
    }

    String[] parts = firstLine.split(" ");
    URI target = URI.create(parts[1]);
    int port = target.getPort() == -1 ? 80 : target.getPort();
    String path = target.getRawPath();
    if (path == null || path.isEmpty()) {
      path = "/";
    }
    if (target.getRawQuery() != null && !target.getRawQuery().isEmpty()) {
      path += "?" + target.getRawQuery();
    }

    try (Socket upstream = new Socket(target.getHost(), port)) {
      OutputStream upstreamOut = upstream.getOutputStream();
      upstreamOut.write((parts[0] + " " + path + " HTTP/1.1\r\n").getBytes(StandardCharsets.US_ASCII));
      for (Map.Entry<String, String> header : headers.entrySet()) {
        upstreamOut.write(
            (header.getKey() + ": " + header.getValue() + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
      }
      upstreamOut.write("\r\n".getBytes(StandardCharsets.US_ASCII));
      upstreamOut.flush();

      transfer(upstream, clientOut);
    }
  }

  private static void transfer(Socket source, Socket destination) {
    try {
      InputStream inputStream = source.getInputStream();
      OutputStream outputStream = destination.getOutputStream();
      byte[] buffer = new byte[8 * 1024];
      int read;
      while ((read = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, read);
        outputStream.flush();
      }
    } catch (IOException ignored) {
    }
  }

  private static void transfer(Socket source, OutputStream destination) {
    try {
      InputStream inputStream = source.getInputStream();
      byte[] buffer = new byte[8 * 1024];
      int read;
      while ((read = inputStream.read(buffer)) != -1) {
        destination.write(buffer, 0, read);
        destination.flush();
      }
    } catch (IOException ignored) {
    }
  }

  public boolean awaitConnect(long timeout, TimeUnit unit) throws InterruptedException {
    return connectLatch.await(timeout, unit);
  }

  public String connectRequestLine() {
    return connectLine.get();
  }

  public String connectHeader(String name) {
    Map<String, String> headers = connectHeaders.get();
    return connectHeaderFrom(headers, name);
  }

  private static String connectHeaderFrom(Map<String, String> headers, String name) {
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }

  @Override
  public void close() throws IOException {
    running = false;
    serverSocket.close();
  }
}
