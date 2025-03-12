package datadog.common.socket;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.trace.api.Config;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

    int testTimeout = 3000;
    Path socketPath = getSocketPath();
    UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);
    startServer(socketAddress);
    TunnelingJdkSocket clientSocket = createClient(socketPath);

    // Test that the socket unblocks when timeout is set to >0
    clientSocket.setSoTimeout(1000);
    assertTimeoutPreemptively(
        Duration.ofMillis(testTimeout), () -> clientSocket.getInputStream().read());

    // Test that the socket blocks indefinitely when timeout is set to 0, per
    // https://docs.oracle.com/en/java/javase/16/docs/api//java.base/java/net/Socket.html#setSoTimeout(int).
    clientSocket.setSoTimeout(0);
    boolean infiniteTimeOut = false;
    try {
      assertTimeoutPreemptively(
          Duration.ofMillis(testTimeout), () -> clientSocket.getInputStream().read());
    } catch (AssertionError e) {
      infiniteTimeOut = true;
    }
    if (!infiniteTimeOut) {
      fail("Test failed: Expected infinite blocking when timeout is set to 0.");
    }

    clientSocket.close();
    isServerRunning.set(false);
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
