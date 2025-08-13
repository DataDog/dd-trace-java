package datadog.trace.agent.test.utils;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PortUtils {

  public static int UNUSABLE_PORT = 61;

  private static final int FREE_PORT_RANGE_START = 20000;
  private static final int FREE_PORT_RANGE_END = 40000;
  private static final int PORT_POOL_SIZE = 200;

  private static final ServerSocket baseSocket;
  private static final int basePort;
  private static final int lastPort;
  private static final AtomicInteger nextPort = new AtomicInteger(0);

  static {
    // We try to allocate a pool of PORT_POOL_SIZE consecutive ports for this process, and make it
    // non-overlapping with other test processes. The first process tries to bind to port 20000. If
    // it works, it'll leave that socket bound for the rest of the process execution (as a cheap
    // lock), and use 20000-20199 round robin. Next process tries to bind to 20000, it fails and
    // tries 20200, if it works, it'll use 20200-23999.... So each process tries to use a
    // non-overlapping batch of 200 ports.
    ServerSocket s = null;
    for (int i = FREE_PORT_RANGE_START; i < FREE_PORT_RANGE_END; i += PORT_POOL_SIZE) {
      s = openSocket(i);
      if (s != null) {
        break;
      }
    }
    baseSocket = s;
    if (s != null) {
      basePort = baseSocket.getLocalPort();
      lastPort = basePort + PORT_POOL_SIZE - 1;
      nextPort.set(basePort + 1);
    } else {
      basePort = 0;
      lastPort = 0;
    }
  }

  /** Open up a random, reusable port. */
  public static int randomOpenPort() {
    if (basePort > 0) {
      for (int i = 0; i < PORT_POOL_SIZE; i++) {
        final int port = nextPort.getAndUpdate(x -> (x >= lastPort) ? basePort + 1 : x + 1);
        ServerSocket socket = openSocket(port);
        if (null != socket) {
          try {
            socket.close();
            return socket.getLocalPort();
          } catch (final IOException ioe) {
            // Ignore
          }
        }
      }
    }

    ServerSocket socket = randomOpenSocket();
    if (null != socket) {
      try {
        socket.close();
        return socket.getLocalPort();
      } catch (final IOException ioe) {
        ioe.printStackTrace();
        return -1;
      }
    } else {
      return -1;
    }
  }

  /** Open up a random, server socket and keep it open. */
  public static ServerSocket randomOpenSocket() {
    return openSocket(0);
  }

  private static ServerSocket openSocket(final int port) {
    try {
      ServerSocket socket = new ServerSocket(port);
      socket.setReuseAddress(true);
      return socket;
    } catch (final IOException ioe) {
      ioe.printStackTrace();
      return null;
    }
  }

  private static boolean isPortOpen(final int port) {
    return isPortOpen(null, port);
  }

  private static boolean isPortOpen(String host, int port) {
    try (final Socket socket = new Socket(host, port)) {
      return true;
    } catch (final IOException e) {
      return false;
    }
  }

  public static void waitForPortToOpen(
      final int port, final long timeout, final TimeUnit unit, final Process process) {
    final long startedAt = System.currentTimeMillis();
    final long waitUntil = startedAt + unit.toMillis(timeout);

    while (System.currentTimeMillis() < waitUntil) {
      try {
        Thread.sleep(100);
      } catch (final InterruptedException e) {
        throw new RuntimeException("Interrupted while waiting for " + port + " to be opened");
      }

      if (!process.isAlive()) {
        int exitCode = process.exitValue();
        if (exitCode != 0) {
          throw new RuntimeException(
              "Process exited abnormally exitCode="
                  + exitCode
                  + " before port="
                  + port
                  + " was opened");
        } else {
          throw new RuntimeException("Process finished before port=" + port + " was opened");
        }
      }

      if (isPortOpen(port)) {
        return;
      }
    }

    throw new RuntimeException(
        "Timed out waiting for port "
            + port
            + " to be opened, started to wait at: "
            + startedAt
            + ", timed out at: "
            + System.currentTimeMillis());
  }

  public static void waitForPortToOpen(String host, int port, long timeout, TimeUnit unit) {
    waitForPort(host, port, timeout, unit, true);
  }

  public static void waitForPortToOpen(int port, long timeout, TimeUnit unit) {
    waitForPortToOpen(null, port, timeout, unit);
  }

  public static void waitForPortToClose(int port, long timeout, TimeUnit unit) {
    waitForPort(null, port, timeout, unit, false);
  }

  private static void waitForPort(
      String host, int port, long timeout, TimeUnit unit, boolean open) {
    long waitNanos = unit.toNanos(timeout);
    long start = System.nanoTime();
    String state = open ? "open" : "closed";

    while (System.nanoTime() - start < waitNanos) {
      if (isPortOpen(host, port) == open) {
        return;
      }

      try {
        Thread.sleep(100);
      } catch (final InterruptedException e) {
        throw new RuntimeException(
            "Interrupted while waiting for "
                + (host != null ? host + ":" : "")
                + port
                + " to be "
                + state);
      }
    }

    throw new RuntimeException(
        "Timed out waiting for port "
            + (host != null ? host + ":" : "")
            + port
            + " to be "
            + state);
  }
}
