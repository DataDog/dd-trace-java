package datadog.common.socket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.io.File;
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
}
