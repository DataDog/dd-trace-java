package datadog.common.socket;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import jnr.unixsocket.UnixSocketAddress;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TunnelingJdkSocketTest {

  private final AtomicBoolean running = new AtomicBoolean(false);

  @Test
  public void testTimeout() throws Exception {
    Assertions.assertEquals(1 + 1, 3); // should fail

    // create test path
    Path socketPath = Files.createTempFile("testSocket", null);
    // start server
    startServer(socketPath);
    // create client socket
    TunnelingJdkSocket clientSocket = createClient(socketPath);

    // attempt to read from empty socket (read should block indefinitely)
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> clientSocket.getInputStream().read());

    // clean up client, server, and path
    clientSocket.close();
    running.set(false);
    Files.deleteIfExists(socketPath);
  }

  private void startServer(Path socketPath) {
    Thread serverThread =
        new Thread(
            () -> {
              // open and bind server to socketPath
              try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
                serverChannel.socket().bind(new UnixSocketAddress(socketPath.toFile()));
                // accept connections made to the server
                running.set(true);
                while (running.get()) {
                  serverChannel.accept();
                }
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });

    // start server in separate thread
    serverThread.start();
  }

  private TunnelingJdkSocket createClient(Path socketPath) throws IOException {
    // create client socket
    TunnelingJdkSocket clientSocket = new TunnelingJdkSocket(socketPath);
    // set timeout to one second
    clientSocket.setSoTimeout(1000);
    return clientSocket;
  }
}
