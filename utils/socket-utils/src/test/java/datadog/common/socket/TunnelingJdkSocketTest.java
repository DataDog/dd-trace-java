package datadog.common.socket;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TunnelingJdkSocketTest {

  private final AtomicBoolean running = new AtomicBoolean(false);

  @Test
  public void testTimeout() throws Exception {
    Assertions.assertEquals(1 + 1, 2);

    // set test socket path
    Path socketPath = getSocketPath();
    // start server
    startServer(socketPath);

    // timeout after two seconds if server doesn't start
    long startTime = System.currentTimeMillis();
    long timeout = 2000;
    while (!running.get()) {
      Thread.sleep(100);
      if (System.currentTimeMillis() - startTime > timeout) {
        System.out.println("Timeout waiting for server to start.");
        break;
      }
    }

    // create client socket
    TunnelingJdkSocket clientSocket = createClient(socketPath);

    // attempt to read from empty socket (read should block indefinitely)
    System.out.println("Test is starting...");
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> clientSocket.getInputStream().read());

    // clean up client, server, and path
    clientSocket.close();
    running.set(false);
    Files.deleteIfExists(socketPath);
    System.out.println("Client, server, and path cleaned.");
  }

  private Path getSocketPath() throws IOException {
    Path socketPath = Files.createTempFile("testSocket", ".sock");
    Files.delete(socketPath);
    socketPath.toFile().deleteOnExit();
    return socketPath;
  }

  private void startServer(Path socketPath) {
    Thread serverThread =
        new Thread(
            () -> {
              // open and bind server to socketPath
              try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
                System.out.println("serverChannel is open.");
                serverChannel.configureBlocking(false);
                System.out.println("serverChannel is not blocking.");
                serverChannel.socket().bind(UnixDomainSocketAddress.of(socketPath));
                // accept connections made to the server
                running.set(true);
                System.out.println("Server is running and ready to accept connections.");
                while (running.get()) {
                  serverChannel.accept();
                  System.out.println("Server is accepting connections.");
                }
              } catch (IOException e) {
                System.out.println("Server encountered error with accepting a connection.");
                // clean up server and path
                running.set(false);
                try {
                  Files.deleteIfExists(socketPath);
                } catch (IOException ex) {
                  throw new RuntimeException(ex);
                }
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
    System.out.println("Client set timeout.");

    if (clientSocket.isConnected()) {
      System.out.println("Client connected successfully.");
    } else {
      System.out.println("Client failed to connect.");
    }

    return clientSocket;
  }
}
