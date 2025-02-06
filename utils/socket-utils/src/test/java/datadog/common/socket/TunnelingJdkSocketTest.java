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

  private static final AtomicBoolean is_server_running = new AtomicBoolean(false);
  private final int client_timeout = 1000;
  private final int test_timeout = 3000;

  @Test
  public void testTimeout() throws Exception {
    Path socketPath = getSocketPath();
    UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);
    startServer(socketAddress);
    TunnelingJdkSocket clientSocket = createClient(socketPath);

    assertTimeoutPreemptively(
        Duration.ofMillis(test_timeout), () -> clientSocket.getInputStream().read());

    clientSocket.close();
    is_server_running.set(false);
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
                is_server_running.set(true);

                while (is_server_running.get()) {
                  SocketChannel clientChannel = serverChannel.accept();
                }
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    serverThread.start();
  }

  private TunnelingJdkSocket createClient(Path socketPath) throws IOException {
    TunnelingJdkSocket clientSocket = new TunnelingJdkSocket(socketPath);
    clientSocket.connect(new InetSocketAddress("localhost", 0));
    clientSocket.setSoTimeout(client_timeout);
    return clientSocket;
  }
}
