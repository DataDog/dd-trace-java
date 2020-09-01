package datadog.trace.agent.test.utils;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

public class PortUtils {

  public static int UNUSABLE_PORT = 61;

  /** Open up a random, reusable port. */
  public static int randomOpenPort() {
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
    try {
      ServerSocket socket = new ServerSocket(0);
      socket.setReuseAddress(true);
      return socket;
    } catch (final IOException ioe) {
      ioe.printStackTrace();
      return null;
    }
  }

  private static boolean isPortOpen(final int port) {
    try (final Socket socket = new Socket((String) null, port)) {
      return true;
    } catch (final IOException e) {
      return false;
    }
  }

  public static void waitForPortToOpen(
      final int port, final long timeout, final TimeUnit unit, final Process process) {
    final long waitUntil = System.currentTimeMillis() + unit.toMillis(timeout);

    while (System.currentTimeMillis() < waitUntil) {
      try {
        Thread.sleep(100);
      } catch (final InterruptedException e) {
        throw new RuntimeException("Interrupted while waiting for " + port + " to be opened");
      }

      // Note: we should have used `process.isAlive()` here but it is java8 only
      try {
        process.exitValue();
        throw new RuntimeException("Process died before port " + port + " was opened");
      } catch (final IllegalThreadStateException e) {
        // process is still alive, things are good.
      }

      if (isPortOpen(port)) {
        return;
      }
    }

    throw new RuntimeException("Timed out waiting for port " + port + " to be opened");
  }

  public static void waitForPortToOpen(int port, long timeout, TimeUnit unit) {
    waitForPort(port, timeout, unit, true);
  }

  public static void waitForPortToClose(int port, long timeout, TimeUnit unit) {
    waitForPort(port, timeout, unit, false);
  }

  private static void waitForPort(int port, long timeout, TimeUnit unit, boolean open) {
    long waitNanos = unit.toNanos(timeout);
    long start = System.nanoTime();
    String state = open ? "open" : "closed";

    while (System.nanoTime() - start < waitNanos) {
      if (isPortOpen(port) == open) {
        return;
      }

      try {
        Thread.sleep(100);
      } catch (final InterruptedException e) {
        throw new RuntimeException("Interrupted while waiting for " + port + " to be " + state);
      }
    }

    throw new RuntimeException("Timed out waiting for port " + port + " to be " + state);
  }
}
