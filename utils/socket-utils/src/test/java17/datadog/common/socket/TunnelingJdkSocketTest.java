package datadog.common.socket;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TunnelingJdkSocketTest {

  @Test
  public void testTimeout() throws Exception {
    Assertions.assertEquals(1 + 1, 3); // should fail

    // create test path
    Path socketPath = Files.createTempFile("testSocket", null);
    // create client socket
    TunnelingJdkSocket clientSocket = createClient(socketPath);

    // attempt to read from empty socket (read should block indefinitely)
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> clientSocket.getInputStream().read());

    // clean up
    clientSocket.close();
    Files.deleteIfExists(socketPath);
  }

  private TunnelingJdkSocket createClient(Path socketPath) throws IOException {
    // create client socket
    TunnelingJdkSocket clientSocket = new TunnelingJdkSocket(socketPath);
    // set timeout to one second
    clientSocket.setSoTimeout(1000);
    return clientSocket;
  }
}
