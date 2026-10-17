package datadog.trace.core.taginterceptor;

import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.KnownTagCodec;
import org.junit.jupiter.api.Test;

/**
 * Pins how {@link InterceptHandler} finds a handler: the switch in {@link
 * InterceptHandler#forSerial} only repeats some of the table's entries, and the table relies on
 * intercepted tags taking the lowest serials. Whether each intercepted tag has a handler is {@code
 * TagInterceptorTest}'s job.
 */
class InterceptHandlerTest {
  @Test
  void theSwitchAgreesWithTheTableForEverySerial() {
    for (int serial = 0; serial < KnownTagCodec.serialLimit(); ++serial) {
      assertSame(
          InterceptHandler.bySerial(serial),
          InterceptHandler.forSerial(serial),
          "serial " + serial);
    }
  }

  @Test
  void everyHandlerIsInTheTableUnderItsOwnSerial() {
    for (InterceptHandler handler : InterceptHandler.values()) {
      if (handler != InterceptHandler.SPLIT_BY_TAGS) {
        assertSame(handler, InterceptHandler.bySerial(handler.serial), handler.name());
      }
    }
  }
}
