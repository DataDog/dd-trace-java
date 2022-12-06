package datadog.trace.util.stacktrace;

import static datadog.trace.util.stacktrace.StackWalkerTestUtil.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class DefaultStackWalkerTest {

  private final StackWalker stackWalker = new DefaultStackWalker();

  @Test
  public void defaultStackWalker_must_be_enabled() {
    assertTrue(stackWalker.isEnabled());
  }

  @Test
  public void walk_from_non_datadog_class() {
    // When
    final List<StackTraceElement> stack = getStackWalkFrom(stackWalker, NOT_DD_CLASS_NAME);
    // Then
    assertFalse(stack.isEmpty());
    assertEquals(NOT_DD_CLASS_NAME, stack.get(0).getClassName());
  }

  @Test
  public void walk_from_datadog_class() {
    // When
    final List<StackTraceElement> stack = getStackWalkFrom(stackWalker, DD_CLASS_NAME);
    // Then
    assertFalse(stack.isEmpty());
    assertNotEquals(DD_CLASS_NAME, stack.get(0).getClassName());
  }

  @Test
  public void walk_from_datadog_iast_class() {
    // When
    final List<StackTraceElement> stack = getStackWalkFrom(stackWalker, DD_IAST_CLASS_NAME);
    // Then
    assertFalse(stack.isEmpty());
    assertNotEquals(DD_IAST_CLASS_NAME, stack.get(0).getClassName());
  }
}
