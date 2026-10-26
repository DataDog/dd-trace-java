package datadog.trace.util.stacktrace;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.util.DDJavaSpecification;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JDK9StackWalkerTest extends DDJavaSpecification {

  private final JDK9StackWalker walker = new JDK9StackWalker();

  @Test
  void stackWalkerEnabled() {
    // when
    boolean enabled = walker.isEnabled();

    // then
    assertTrue(enabled);
  }

  @Test
  void walkRetrievesStackTraceElements() {
    // when
    List<StackTraceElement> stackTrace = getStackTrace();

    // then
    assertFalse(stackTrace.isEmpty());
  }

  @Test
  void walkRetrievesNoDatadogStackElements() {
    // when
    List<StackTraceElement> stackTrace = getStackTrace();

    // then
    assertEquals(
        emptyList(),
        stackTrace.stream()
            .filter(element -> element.getClassName().startsWith("datadog"))
            .collect(Collectors.toList()));
  }

  private List<StackTraceElement> getStackTrace() {
    return walker.walk(stack -> stack.collect(Collectors.toList()));
  }
}
