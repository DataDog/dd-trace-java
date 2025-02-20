package datadog.common.socket;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
  private final int clientTimeout = 1000;
  private final int testTimeout = 3000;

  @Test
  public void testTimeout() throws Exception {
    Path socketPath = getSocketPath();
    UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);
    startServer(socketAddress);
    TunnelingJdkSocket clientSocket = createClient(socketPath);

    assertTimeoutPreemptively(
        Duration.ofMillis(testTimeout), () -> clientSocket.getInputStream().read());

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
    clientSocket.setSoTimeout(clientTimeout);
    return clientSocket;
  }
}
