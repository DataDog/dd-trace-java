package datadog.trace.agent.test.utils;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.BitSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.SneakyThrows;

public class PortUtils {
  private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(1);
  // Access guarded by randomOpenPort method synchronization.
  private static final BitSet USED_PORTS = new BitSet();

  public static int UNUSABLE_PORT = 61;

  /** Open up a random, reusable port. */
  @SneakyThrows
  public static synchronized int randomOpenPort() {
    final long startTime = System.nanoTime();
    int port = 0;
    while (port <= 0 || USED_PORTS.get(port)) {
      if (startTime + TIMEOUT < System.nanoTime()) {
        throw new TimeoutException("Timeout while getting randomOpenPort");
      }
      try {
        final ServerSocket socket = new ServerSocket(0);
        socket.setReuseAddress(true);
        socket.close();
        port = socket.getLocalPort();
      } catch (final IOException ioe) {
      }
    }
    USED_PORTS.set(port);
    return port;
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
}
