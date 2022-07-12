package datadog.trace.util.stacktrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.foo.Foo;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class StackWalkerTest {

  @Test
  public void stackTraceGenerator_instance_must_be_enabled() {
    // When
    StackWalker stackWalker = StackWalker.INSTANCE;
    // Then
    assertTrue(stackWalker.isEnabled());
  }

  @Test
  public void retrieve_stackTraceElements() {
    // When
    Stream<StackTraceElement> stackTraceElements = StackWalker.INSTANCE.walk();
    // Then
    assertNotEquals(stackTraceElements.count(), 0);
  }

  @Test
  public void retrieve_fist_stack_element_not_in_DD_trace_project() {

    Runnable test =
        () -> {
          Stream<StackTraceElement> stackTraceElements = StackWalker.INSTANCE.walk();
          // Then
          assertEquals(
              stackTraceElements.findFirst().get().toString(),
              "com.foo.Foo$Foo3.foo3Method(Foo.java:22)");
        };

    Foo.fooMethod(test);
  }

  @Test
  public void filter_DataDog_Trace_classes_from_StackTraceElements() {
    // When
    Stream<StackTraceElement> stackTraceElements = StackWalker.INSTANCE.walk();
    // Then
    assertTrue(
        stackTraceElements.noneMatch(
            stackTraceElement -> stackTraceElement.toString().startsWith("datadog.trace.")));
  }

  @Test
  public void filter_DataDog_AppSec_classes_from_StackTraceElements() {
    // When
    Stream<StackTraceElement> stackTraceElements = StackWalker.INSTANCE.walk();
    // Then
    assertTrue(
        stackTraceElements.noneMatch(
            stackTraceElement -> stackTraceElement.toString().startsWith("com.datadog.appsec.")));
  }
}
