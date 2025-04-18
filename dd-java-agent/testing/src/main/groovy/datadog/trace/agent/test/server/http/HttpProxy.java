package datadog.trace.agent.test.server.http;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Adapted from
// https://github.com/stefano-lupo/Java-Proxy-Server/blob/master/src/RequestHandler.java
public final class HttpProxy implements Closeable {
  private final ExecutorService executorService;
  private final ServerSocket serverSocket;
  private final Collection<Socket> openSockets = new CopyOnWriteArrayList<>();
  public final int port;
  private final AtomicInteger requestCount = new AtomicInteger();

  @SuppressForbidden
  HttpProxy() throws IOException {
    serverSocket = new ServerSocket(0); // random port
    serverSocket.setSoTimeout(0);
    port = serverSocket.getLocalPort();
    executorService =
        Executors.newCachedThreadPool(
            r -> {
              final Thread thread = new Thread(null, r, "Http Proxy: " + port);
              thread.setDaemon(true);
              return thread;
            });
    executorService.execute(new SocketAcceptor());
    System.out.println("Started proxy server " + this + " on port " + port);
  }

  @Override
  public void close() throws IOException {
    executorService.shutdown();
    serverSocket.close();
    for (Socket socket : openSockets) {
      socket.close();
    }
    try {
      executorService.awaitTermination(1, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
    }
  }

  public int requestCount() {
    return requestCount.get();
  }

  private final class SocketAcceptor implements Runnable {
    @Override
    public void run() {
      while (!Thread.currentThread().isInterrupted() && !serverSocket.isClosed()) {
        try {
          // Only accepts one request at a time (not multi-threaded).
          Socket clientToProxy = serverSocket.accept();
          requestCount.incrementAndGet();
          clientToProxy.setSoTimeout(0);
          openSockets.add(clientToProxy);
          executorService.execute(new Handler(clientToProxy));
        } catch (Exception e) {
          if (!executorService.isShutdown()) {
            e.printStackTrace();
          }
        }
      }
    }
  }

  @SuppressForbidden
  public final class Handler implements Runnable {
    private final Socket clientToProxy;

    public Handler(Socket clientToProxy) {
      this.clientToProxy = clientToProxy;
    }

    @Override
    @SuppressForbidden
    public void run() {
      try {
        BufferedReader clientToProxyIn =
            new BufferedReader(new InputStreamReader(clientToProxy.getInputStream()));
        OutputStreamWriter clientToProxyOut =
            new OutputStreamWriter(clientToProxy.getOutputStream());

        String requestString = clientToProxyIn.readLine();
        System.out.println("Request Received " + requestString);
        String request = requestString.substring(0, requestString.indexOf(' '));
        assert request.equals("CONNECT");

        String urlString = requestString.substring(requestString.indexOf(' ') + 1);
        urlString = urlString.substring(0, urlString.indexOf(' '));

        System.out.println("HTTPS Request for : " + urlString);

        String remainder = null;
        while (remainder == null || !remainder.isEmpty()) {
          remainder = clientToProxyIn.readLine();
          System.out.println("Remainder(" + remainder.length() + "):" + remainder);
        }

        String[] hostSplit = urlString.split(":");
        assert hostSplit.length == 2;

        Socket proxyToServer = new Socket(hostSplit[0], Integer.parseInt(hostSplit[1]));
        openSockets.add(proxyToServer);
        proxyToServer.setSoTimeout(0);

        clientToProxyOut.write("HTTP/1.0 200 Connection established\r\n\r\n");
        clientToProxyOut.flush();

        executorService.execute(new AsyncPipe(clientToProxy, proxyToServer));
        executorService.execute(new AsyncPipe(proxyToServer, clientToProxy));
        System.out.println("Connection pipes established");
      } catch (Exception e) {
        if (!executorService.isShutdown()) {
          e.printStackTrace();
        }
      }
    }
  }

  private class AsyncPipe implements Runnable {
    private final Socket input;
    private final Socket output;

    @SuppressForbidden
    private AsyncPipe(Socket input, Socket output) {
      this.input = input;
      this.output = output;
      System.out.println("Starting: " + this);
    }

    @Override
    @SuppressForbidden
    public void run() {
      try {
        // Read byte by byte from client and send directly to server
        byte[] buffer = new byte[4096];
        int read;
        InputStream inputStream = input.getInputStream();
        OutputStream outputStream = output.getOutputStream();
        do {
          read = inputStream.read(buffer);
          System.out.println(this + " -> " + read);
          if (read > 0) {
            outputStream.write(buffer, 0, read);
            if (inputStream.available() < 1) {
              outputStream.flush();
            }
          }
        } while (read >= 0 && !executorService.isShutdown());
        System.out.println("Done: " + this);
      } catch (SocketException e) {
        // ignore sockets getting closed.
      } catch (IOException e) {
        if (!executorService.isShutdown()) {
          e.printStackTrace();
        }
      } finally {
        openSockets.remove(input);
        try {
          output.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }
}
