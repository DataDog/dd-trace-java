package datadog.common.socket;

import static datadog.common.socket.NamedPipeSocketFactory.CONNECT_TIMEOUT_MILLIS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NamedPipeSocketFactoryTest {
  /** The Agent port HTTP clients ask for. The factory opens the pipe whatever the address. */
  private static final int AGENT_PORT = 8126;

  @ParameterizedTest
  @ValueSource(strings = {"datadog-apm", "\\\\.\\pipe\\datadog-apm"})
  void opensTheConfiguredPipeWithOrWithoutItsPrefix(String pipeName) {
    NamedPipeSocketFactory factory = new NamedPipeSocketFactory(pipeName);

    assertEquals(new File("\\\\.\\pipe\\datadog-apm"), factory.getPipe());
  }

  @Test
  void opensThePipeWhateverTheRequestedAddress(@TempDir Path tempDir) throws Exception {
    NamedPipeSocketFactory factory = new NamedPipeSocketFactory(tempDir.resolve("pipe").toFile());
    InetAddress loopback = InetAddress.getLoopbackAddress();

    try (Socket byHostName = factory.createSocket("localhost", AGENT_PORT);
        Socket byHostNameAndLocalAddress =
            factory.createSocket("localhost", AGENT_PORT, loopback, 0);
        Socket byAddress = factory.createSocket(loopback, AGENT_PORT);
        Socket byAddressAndLocalAddress = factory.createSocket(loopback, AGENT_PORT, loopback, 0)) {
      assertInstanceOf(NamedPipeSocket.class, byHostName);
      assertInstanceOf(NamedPipeSocket.class, byHostNameAndLocalAddress);
      assertInstanceOf(NamedPipeSocket.class, byAddress);
      assertInstanceOf(NamedPipeSocket.class, byAddressAndLocalAddress);
    }
  }

  @Test
  void createsANewSocketForEachConnection(@TempDir Path tempDir) throws Exception {
    // a regular file stands in for the pipe, since NamedPipeSocket only opens it as a file
    File pipe = tempDir.resolve("pipe").toFile();
    NamedPipeSocketFactory factory = new NamedPipeSocketFactory(pipe);

    try (Socket first = factory.createSocket();
        Socket second = factory.createSocket()) {
      assertNotSame(first, second);

      first.close();
      assertFalse(second.isClosed());
    }
  }

  @Test
  void retriesUntilThePipeCanBeOpened(@TempDir Path tempDir) throws Exception {
    // the file can't be opened until its directory exists, like a busy or missing pipe
    File directory = tempDir.resolve("server").toFile();
    NamedPipeSocketFactory factory = new NamedPipeSocketFactory(new File(directory, "pipe"));
    Thread server =
        new Thread(() -> {
          try {
            Thread.sleep(50);
          } catch (InterruptedException ignored) {
            // create the directory now
          }
          directory.mkdir();
        });
    server.start();

    try (Socket socket = factory.createSocket()) {
      assertFalse(socket.isClosed());
    } finally {
      server.join();
    }
  }

  @Test
  void givesUpAfterTheConnectTimeout(@TempDir Path tempDir) {
    File pipe = tempDir.resolve("missing").resolve("pipe").toFile();
    NamedPipeSocketFactory factory = new NamedPipeSocketFactory(pipe);

    long start = System.nanoTime();
    assertThrows(FileNotFoundException.class, factory::createSocket);
    assertTrue(NANOSECONDS.toMillis(System.nanoTime() - start) >= CONNECT_TIMEOUT_MILLIS);
  }

  @Test
  void stopsWaitingForThePipeWhenInterrupted(@TempDir Path tempDir) {
    File pipe = tempDir.resolve("missing").resolve("pipe").toFile();
    NamedPipeSocketFactory factory = new NamedPipeSocketFactory(pipe);

    Thread.currentThread().interrupt();
    try {
      long start = System.nanoTime();
      assertThrows(FileNotFoundException.class, factory::createSocket);
      assertTrue(NANOSECONDS.toMillis(System.nanoTime() - start) < CONNECT_TIMEOUT_MILLIS);
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt status is kept");
    } finally {
      Thread.interrupted();
    }
  }
}
