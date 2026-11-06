package datadog.trace.agent.test.utils;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PortUtils {

  public static int UNUSABLE_PORT = 61;
  private static final int PORT_CONNECT_TIMEOUT_MS = 1000;

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
    try (final Socket socket = new Socket()) {
      InetSocketAddress address =
          host == null
              ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port)
              : new InetSocketAddress(host, port);
      socket.connect(address, PORT_CONNECT_TIMEOUT_MS);
      return true;
    } catch (final IOException e) {
      return false;
    }
  }

  @SuppressForbidden
  public static void waitForPortToOpen(
      final int port, final long timeout, final TimeUnit unit, final Process process) {
    final long startedAt = System.currentTimeMillis();
    final long waitUntil = startedAt + unit.toMillis(timeout);
    final long progressIntervalMillis = TimeUnit.SECONDS.toMillis(30);
    long nextProgressAt = startedAt + progressIntervalMillis;
    final long pid = tryGetPid(process);

    System.err.println(
        "[PortUtils] Waiting up to "
            + unit.toMillis(timeout)
            + "ms for port "
            + port
            + " to open (process pid="
            + pid
            + ")");

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
        System.err.println(
            "[PortUtils] Port "
                + port
                + " opened after "
                + (System.currentTimeMillis() - startedAt)
                + "ms");
        return;
      }

      long now = System.currentTimeMillis();
      if (now >= nextProgressAt) {
        System.err.println(
            "[PortUtils] Still waiting for port "
                + port
                + " (pid="
                + pid
                + ") elapsed="
                + (now - startedAt)
                + "ms alive="
                + process.isAlive());
        nextProgressAt = now + progressIntervalMillis;
      }
    }

    // Timeout: capture diagnostics before throwing so the next failure tells us what was hung.
    System.err.println(
        "[PortUtils] Timed out waiting for port "
            + port
            + " after "
            + unit.toMillis(timeout)
            + "ms; pid="
            + pid
            + " alive="
            + process.isAlive());
    if (pid > 0) {
      requestChildThreadDump(pid);
    }
    dumpTestJvmThreads();

    throw new RuntimeException(
        "Timed out waiting for port "
            + port
            + " to be opened, started to wait at: "
            + startedAt
            + ", timed out at: "
            + System.currentTimeMillis());
  }

  @SuppressForbidden
  private static long tryGetPid(Process process) {
    try {
      Field pidField = process.getClass().getDeclaredField("pid");
      pidField.setAccessible(true);
      return pidField.getLong(process);
    } catch (Throwable t) {
      System.err.println("[PortUtils] Could not extract child pid: " + t);
      return -1;
    }
  }

  @SuppressForbidden
  private static void requestChildThreadDump(long pid) {
    String osName = System.getProperty("os.name", "").toLowerCase();
    if (osName.startsWith("windows")) {
      System.err.println("[PortUtils] Skipping SIGQUIT thread dump on Windows (pid=" + pid + ")");
      return;
    }
    try {
      System.err.println(
          "[PortUtils] Sending SIGQUIT (kill -3) to pid " + pid + " to trigger thread dump");
      Process kill =
          new ProcessBuilder("kill", "-3", String.valueOf(pid)).redirectErrorStream(true).start();
      kill.waitFor(5, TimeUnit.SECONDS);
      // HotSpot writes the dump to the child stderr (captured in the test process log).
      // IBM J9 writes a javacore.* file (see -Xdump:directory, default /tmp in smoke tests).
      // Give the JVM a moment to finish writing before we throw.
      Thread.sleep(2000);
      System.err.println("[PortUtils] SIGQUIT delivered; check child process log / javacore files");
    } catch (Throwable t) {
      System.err.println("[PortUtils] Failed to send SIGQUIT to pid " + pid + ": " + t);
    }
  }

  @SuppressForbidden
  private static void dumpTestJvmThreads() {
    System.err.println("[PortUtils] === Test JVM thread dump ===");
    Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
    for (Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
      Thread t = entry.getKey();
      System.err.println("\"" + t.getName() + "\" id=" + t.getId() + " state=" + t.getState());
      for (StackTraceElement ste : entry.getValue()) {
        System.err.println("\tat " + ste);
      }
      System.err.println();
    }
    System.err.println("[PortUtils] === End of test JVM thread dump ===");
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
