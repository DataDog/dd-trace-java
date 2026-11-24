package datadog.trace.instrumentation.jetty12;

import static datadog.trace.instrumentation.jetty12.JettyBlockingExceptions.findBlockingException;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.appsec.api.blocking.BlockingException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link JettyBlockingExceptions}. */
class JettyBlockingExceptionsTest {

  @Test
  void findsDirectBlockingException() {
    BlockingException blocking = new BlockingException("blocked");

    assertSame(blocking, findBlockingException(blocking));
  }

  @Test
  void findsWrappedBlockingException() {
    BlockingException blocking = new BlockingException("blocked");

    assertSame(
        blocking, findBlockingException(new RuntimeException(new IllegalStateException(blocking))));
  }

  @Test
  void findsSuppressedBlockingException() {
    BlockingException blocking = new BlockingException("blocked");
    IOException combined = new IOException("write failed");
    combined.addSuppressed(blocking);

    assertSame(blocking, findBlockingException(combined));
  }

  @Test
  void ignoresOtherFailures() {
    assertNull(findBlockingException(null));
    assertNull(findBlockingException(new IOException("reset")));
  }
}
