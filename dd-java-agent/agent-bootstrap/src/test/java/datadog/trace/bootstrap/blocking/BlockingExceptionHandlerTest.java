package datadog.trace.bootstrap.blocking;

import static datadog.trace.bootstrap.blocking.BlockingExceptionHandler.rethrowIfBlockingException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.appsec.api.blocking.BlockingException;
import org.junit.jupiter.api.Test;

class BlockingExceptionHandlerTest {

  @Test
  void testBlockingExceptionHandler() {
    // when
    BlockingException blockingException = new BlockingException();

    // then
    assertThrows(BlockingException.class, () -> rethrowIfBlockingException(blockingException));

    // when
    Throwable result =
        assertDoesNotThrow(() -> rethrowIfBlockingException(new UnsupportedOperationException()));

    // then
    assertInstanceOf(UnsupportedOperationException.class, result);
  }
}
