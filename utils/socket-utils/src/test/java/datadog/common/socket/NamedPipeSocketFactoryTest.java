package datadog.common.socket;

import static datadog.common.socket.NamedPipeSocketFactory.CONNECT_TIMEOUT_MILLIS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.Socket;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NamedPipeSocketFactoryTest {

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
        new Thread(
            () -> {
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
}
