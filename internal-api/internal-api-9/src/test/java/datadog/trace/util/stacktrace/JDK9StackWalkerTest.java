package datadog.trace.util.stacktrace;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class JDK9StackWalkerTest {

  private static final JDK9StackWalker stackWalker = new JDK9StackWalker();

  @Test
  public void defaultStackWalker_must_be_enabled() {
    assertTrue(stackWalker.isEnabled());
  }

  @Test
  public void walk_retrieves_stackTraceElements() {
    // When
    Stream<StackTraceElement> stream = stackWalker.walk();
    // Then
    assertNotEquals(stream.count(), 0);
  }

  @Test
  public void get_stack_trace() {
    // When
    Stream<StackTraceElement> stream = stackWalker.doGetStack();
    // Then
    assertNotEquals(stream.count(), 0);
  }

  @Test
  public void stack_element_not_in_DD_trace_project_is_not_filtered() {
    // when
    Stream<StackTraceElement> stream = Stream.of(element("com.foo.Foo"));
    Stream<StackTraceElement> filtered = stackWalker.doFilterStack(stream);
    // Then
    assertEquals(filtered.count(), 1);
  }

  @Test
  public void filter_DataDog_Trace_classes_from_StackTraceElements() {
    // when
    Stream<StackTraceElement> stream = Stream.of(element("datadog.trace.Foo"));
    Stream<StackTraceElement> filtered = stackWalker.doFilterStack(stream);
    // Then
    assertEquals(filtered.count(), 0);
  }

  @Test
  public void filter_DataDog_AppSec_classes_from_StackTraceElements() {
    // when
    Stream<StackTraceElement> stream = Stream.of(element("com.datadog.appsec.Foo"));
    Stream<StackTraceElement> filtered = stackWalker.doFilterStack(stream);
    // Then
    assertEquals(filtered.count(), 0);
  }

  private StackTraceElement element(final String className) {
    return new StackTraceElement(className, "method", "fileName", 1);
  }
}
