package datadog.common.socket;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import datadog.trace.api.Config;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class TunnelingJdkSocketTest {

  private static final AtomicBoolean isServerRunning = new AtomicBoolean(false);

  @Test
  public void testTimeout() throws Exception {
    if (!Config.get().isJdkSocketEnabled()) {
      System.out.println(
          "TunnelingJdkSocket usage is disabled. Enable it by setting the property 'JDK_SOCKET_ENABLED' to 'true'.");
      return;
    }

    Path socketPath = getSocketPath();
    UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);
    startServer(socketAddress);
    TunnelingJdkSocket clientSocket = createClient(socketPath);
    InputStream inputStream = clientSocket.getInputStream();

    int testTimeout = 1000;
    clientSocket.setSoTimeout(testTimeout);
    assertEquals(testTimeout, clientSocket.getSoTimeout());

    long startTime = System.currentTimeMillis();
    int readResult = inputStream.read();
    long endTime = System.currentTimeMillis();
    long readDuration = endTime - startTime;
    int timeVariance = 100;
    assertTrue(readDuration >= testTimeout && readDuration <= testTimeout + timeVariance);
    assertEquals(0, readResult);

    int newTimeout = testTimeout / 2;
    clientSocket.setSoTimeout(newTimeout);
    assertEquals(newTimeout, clientSocket.getSoTimeout());
    assertTimeoutPreemptively(Duration.ofMillis(testTimeout), () -> inputStream.read());

    // The socket should block indefinitely when timeout is set to 0, per
    // https://docs.oracle.com/en/java/javase/16/docs/api//java.base/java/net/Socket.html#setSoTimeout(int).
    int infiniteTimeout = 0;
    clientSocket.setSoTimeout(infiniteTimeout);
    assertEquals(infiniteTimeout, clientSocket.getSoTimeout());
    try {
      assertTimeoutPreemptively(Duration.ofMillis(testTimeout), () -> inputStream.read());
      fail("Read should block indefinitely with infinite timeout");
    } catch (AssertionError e) {
      // Expected
    }

    int invalidTimeout = -1;
    assertThrows(IllegalArgumentException.class, () -> clientSocket.setSoTimeout(invalidTimeout));

    clientSocket.close();
    assertThrows(SocketException.class, () -> clientSocket.setSoTimeout(testTimeout));
    assertThrows(SocketException.class, () -> clientSocket.getSoTimeout());

    isServerRunning.set(false);
  }

  @Test
  public void testBufferSizes() throws Exception {
    if (!Config.get().isJdkSocketEnabled()) {
      System.out.println(
          "TunnelingJdkSocket usage is disabled. Enable it by setting the property 'JDK_SOCKET_ENABLED' to 'true'.");
      return;
    }

    Path socketPath = getSocketPath();
    UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);
    startServer(socketAddress);
    TunnelingJdkSocket clientSocket = createClient(socketPath);

    assertEquals(TunnelingJdkSocket.DEFAULT_BUFFER_SIZE, clientSocket.getSendBufferSize());
    assertEquals(TunnelingJdkSocket.DEFAULT_BUFFER_SIZE, clientSocket.getReceiveBufferSize());
    assertEquals(TunnelingJdkSocket.DEFAULT_BUFFER_SIZE, clientSocket.getStreamBufferSize());

    int newBufferSize = TunnelingJdkSocket.DEFAULT_BUFFER_SIZE / 2;
    clientSocket.setSendBufferSize(newBufferSize);
    clientSocket.setReceiveBufferSize(newBufferSize / 2);
    assertEquals(newBufferSize, clientSocket.getSendBufferSize());
    assertEquals(newBufferSize / 2, clientSocket.getReceiveBufferSize());
    assertEquals(newBufferSize, clientSocket.getStreamBufferSize());

    int invalidBufferSize = -1;
    assertThrows(
        IllegalArgumentException.class, () -> clientSocket.setSendBufferSize(invalidBufferSize));
    assertThrows(
        IllegalArgumentException.class, () -> clientSocket.setReceiveBufferSize(invalidBufferSize));

    clientSocket.close();
    assertThrows(
        SocketException.class,
        () -> clientSocket.setSendBufferSize(TunnelingJdkSocket.DEFAULT_BUFFER_SIZE));
    assertThrows(
        SocketException.class,
        () -> clientSocket.setReceiveBufferSize(TunnelingJdkSocket.DEFAULT_BUFFER_SIZE));
    assertThrows(SocketException.class, () -> clientSocket.getSendBufferSize());
    assertThrows(SocketException.class, () -> clientSocket.getReceiveBufferSize());
    assertThrows(SocketException.class, () -> clientSocket.getStreamBufferSize());

    isServerRunning.set(false);
  }

  @Test
  public void testFileDescriptorLeak() throws Exception {
    if (!Config.get().isJdkSocketEnabled()) {
      System.out.println(
          "TunnelingJdkSocket usage is disabled. Enable it by setting the property 'JDK_SOCKET_ENABLED' to 'true'.");
      return;
    }
    long initialCount = getFileDescriptorCount();
    System.out.println("Initial file descriptor count: " + initialCount);

    Path socketPath = getSocketPath();
    UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);
    startServer(socketAddress);
    TunnelingJdkSocket clientSocket = createClient(socketPath);

    List<InputStream> streams = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      InputStream inputStream = clientSocket.getInputStream();
      streams.add(inputStream);
      long currentCount = getFileDescriptorCount();
      System.out.println("Iteration " + i + ", File descriptor count: " + currentCount);
      assertTrue(currentCount <= initialCount + 7); // why +7?
    }

    clientSocket.close();
    streams.clear();
    isServerRunning.set(false);

    long finalCount = getFileDescriptorCount();
    System.out.println("Final file descriptor count: " + finalCount);
    assertTrue(finalCount <= initialCount + 3); // why +3?
  }

  private long getFileDescriptorCount() {
    try {
      Process process = Runtime.getRuntime().exec("lsof -p " + getPid());
      int count = 0;
      try (java.io.BufferedReader reader =
          new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
        while (reader.readLine() != null) {
          count++;
        }
      }
      return count;
    } catch (IOException e) {
      throw new RuntimeException("Failed to get file descriptor count", e);
    }
  }

  private String getPid() {
    return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
  }

  private Path getSocketPath() throws IOException {
    Path socketPath = Files.createTempFile("testSocket", null);
    Files.delete(socketPath);
    socketPath.toFile().deleteOnExit();
    return socketPath;
  }

  private static void startServer(UnixDomainSocketAddress socketAddress) {
    Thread serverThread =
        new Thread(
            () -> {
              try (ServerSocketChannel serverChannel =
                  ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                serverChannel.bind(socketAddress);
                isServerRunning.set(true);

                synchronized (isServerRunning) {
                  isServerRunning.notifyAll();
                }

                while (isServerRunning.get()) {
                  SocketChannel clientChannel = serverChannel.accept();
                }
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    serverThread.start();

    synchronized (isServerRunning) {
      while (!isServerRunning.get()) {
        try {
          isServerRunning.wait();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private TunnelingJdkSocket createClient(Path socketPath) throws IOException {
    TunnelingJdkSocket clientSocket = new TunnelingJdkSocket(socketPath);
    clientSocket.connect(new InetSocketAddress("localhost", 0));
    return clientSocket;
  }
}
