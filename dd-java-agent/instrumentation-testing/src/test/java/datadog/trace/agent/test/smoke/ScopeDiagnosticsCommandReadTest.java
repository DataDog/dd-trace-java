package datadog.trace.agent.test.smoke;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScopeDiagnosticsCommandReadTest {
  @Test
  void retriesTransientReadFailure() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    Properties command = new Properties();

    Properties result =
        ScopeDiagnosticsAgent.readCommand(
            () -> {
              if (reads.incrementAndGet() == 1) {
                throw new IOException("temporary read failure");
              }
              return command;
            },
            SECONDS.toNanos(5));

    assertSame(command, result);
    assertEquals(2, reads.get());
  }

  @Test
  void retainsReadFailureWhenDeadlineExpires() {
    IOException failure = new IOException("persistent read failure");

    IOException result =
        assertThrows(
            IOException.class,
            () ->
                ScopeDiagnosticsAgent.readCommand(
                    () -> {
                      throw failure;
                    },
                    0));

    assertSame(failure, result);
  }
}
