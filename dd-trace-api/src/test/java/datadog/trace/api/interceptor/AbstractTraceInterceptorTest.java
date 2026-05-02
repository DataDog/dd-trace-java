package datadog.trace.api.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;
import org.junit.jupiter.api.Test;

class AbstractTraceInterceptorTest {

  @Test
  void priorityIndexIsTakenFromEnum() {
    AbstractTraceInterceptor.Priority priority = AbstractTraceInterceptor.Priority.values()[0];
    AbstractTraceInterceptor interceptor =
        new AbstractTraceInterceptor(priority) {
          @Override
          public Collection<? extends MutableSpan> onTraceComplete(
              Collection<? extends MutableSpan> trace) {
            return null;
          }
        };

    assertEquals(priority.getIdx(), interceptor.priority());
  }
}
