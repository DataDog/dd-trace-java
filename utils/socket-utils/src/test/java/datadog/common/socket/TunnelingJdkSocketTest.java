package datadog.common.socket;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class TunnelingJdkSocketTest {

  private static final AtomicBoolean is_server_running = new AtomicBoolean(false);

  @Test
  public void testTimeout() throws Exception {
    // set socket path and address
    Path socketPath = getSocketPath();
    UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);

    // start server in a separate thread
    startServer(socketAddress, false);

    // create client
    TunnelingJdkSocket clientSocket = createClient(socketPath);

    // expect a failure after three seconds because timeout is not supported yet
    assertTimeoutPreemptively(Duration.ofMillis(3000), () -> clientSocket.getInputStream().read());

    // clean up
    clientSocket.close();
    is_server_running.set(false);
  }

  private Path getSocketPath() throws IOException {
    Path socketPath = Files.createTempFile("testSocket", null);
    Files.delete(socketPath);
    socketPath.toFile().deleteOnExit();
    return socketPath;
  }

  private static void startServer(UnixDomainSocketAddress socketAddress, boolean sendMessage) {
    Thread serverThread =
        new Thread(
            () -> {
              try (ServerSocketChannel serverChannel =
                  ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                serverChannel.bind(socketAddress);
                is_server_running.set(true);

                // wait for client connection
                while (is_server_running.get()) {
                  SocketChannel clientChannel = serverChannel.accept();
                  if (sendMessage) {
                    clientChannel.write(ByteBuffer.wrap("Hello!".getBytes()));
                  }
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
    clientSocket.setSoTimeout(1000);
    return clientSocket;
  }
}
