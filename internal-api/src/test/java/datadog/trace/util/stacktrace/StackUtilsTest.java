package datadog.trace.util.stacktrace;

import static datadog.trace.util.stacktrace.StackUtils.META_STRUCT_KEY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.gateway.RequestContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class StackUtilsTest {

  @Test
  public void test_identity_function() {
    final Throwable source = new RuntimeException();
    final StackTraceElement[] expected = source.getStackTrace();

    final Throwable updated = StackUtils.update(withStack(expected), Function.identity());
    assertArrayEquals(expected, updated.getStackTrace());
  }

  @Test
  public void test_filter_all_datadog() {
    final StackTraceElement[] stack =
        new StackTraceElement[] {
          stack().className("org.junit.jupiter.api.Test").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtilsTest").build(),
          stack().className("java.util.function.Function").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtils").build(),
          stack().className("org.junit.jupiter.api.Assertions").build()
        };
    final StackTraceElement[] expected = new StackTraceElement[] {stack[0], stack[2], stack[4]};

    final Throwable filtered =
        StackUtils.filter(
            withStack(stack), item -> !item.getClassName().startsWith("datadog.trace"));
    assertArrayEquals(expected, filtered.getStackTrace());

    final Throwable filtered2 = StackUtils.filterDatadog(withStack(stack));
    assertArrayEquals(expected, filtered2.getStackTrace());
  }

  @Test
  public void test_stack_filters() {
    final StackTraceElement[] stack =
        new StackTraceElement[] {
          stack().className("org.junit.jupiter.api.Test").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtilsTest").build(),
          stack().className("java.util.function.Function").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtils").build(),
          stack().className("org.junit.jupiter.api.Assertions").build()
        };
    final StackTraceElement[] expected =
        new StackTraceElement[] {stack[0], stack[2], stack[3], stack[4]};

    final Throwable filtered =
        StackUtils.filterFirst(
            withStack(stack), item -> !item.getClassName().startsWith("datadog.trace"));
    assertArrayEquals(expected, filtered.getStackTrace());

    final Throwable filtered2 = StackUtils.filterFirstDatadog(withStack(stack));
    assertArrayEquals(expected, filtered2.getStackTrace());
  }

  @Test
  public void test_filter_until() {
    final StackTraceElement[] stack =
        new StackTraceElement[] {
          stack().className("org.junit.jupiter.api.Test").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtilsTest").build(),
          stack().className("java.util.function.Function").build(),
          stack().className("datadog.trace.util.stacktrace.StackUtils").build(),
          stack().className("org.junit.jupiter.api.Assertions").build()
        };

    final StackTraceElement[] expected = new StackTraceElement[] {stack[4]};
    final Throwable removed =
        StackUtils.filterUntil(
            withStack(stack),
            entry -> entry.getClassName().equals("datadog.trace.util.stacktrace.StackUtils"));
    assertArrayEquals(expected, removed.getStackTrace());

    final Throwable noRemoval = StackUtils.filterUntil(withStack(stack), entry -> false);
    assertArrayEquals(stack, noRemoval.getStackTrace());
  }

  private static Stream<Arguments> test_generateUserCodeStackTrace_Params() {
    return Stream.of(
        Arguments.of((Predicate<StackTraceElement>) stack -> true, false),
        Arguments.of(
            (Predicate<StackTraceElement>) stack -> !stack.getClassName().startsWith("org.junit"),
            true));
  }

  @ParameterizedTest(name = "[{index}]")
  @MethodSource("test_generateUserCodeStackTrace_Params")
  public void test_generateUserCodeStackTrace(
      final Predicate<StackTraceElement> filter, final boolean expected) {
    List<StackTraceFrame> userCodeStack = StackUtils.generateUserCodeStackTrace(filter);
    assertNotNull(userCodeStack);
    int junitFramesCounter = 0;
    for (StackTraceFrame frame : userCodeStack) {
      if (frame.getClass_name() != null && frame.getClass_name().startsWith("org.junit")) {
        junitFramesCounter++;
      }
    }
    if (expected) {
      assertEquals(0, junitFramesCounter);
    } else {
      assertTrue(junitFramesCounter > 0);
    }
  }

  @Test
  public void addStacktraceEventsToAvailableMetaStruct() {
    final RequestContext reqCtx = mock(RequestContext.class);
    final Map<String, List<StackTraceEvent>> batch = new HashMap<>();
    when(reqCtx.getOrCreateMetaStructTop(eq(META_STRUCT_KEY), any())).thenReturn(batch);
    final String productTest = "test";
    final StackTraceEvent event = new StackTraceEvent(new ArrayList<>(0), "java", "id", "message");
    StackUtils.addStacktraceEventsToMetaStruct(
        reqCtx, productTest, Collections.singletonList(event));
    assertTrue(batch.containsKey(productTest));
    assertTrue(batch.get(productTest).contains(event));
  }

  private static Throwable withStack(final StackTraceElement... stack) {
    final Exception exception = new RuntimeException();
    exception.setStackTrace(stack);
    return exception;
  }

  private static StackTraceBuilder stack() {
    return new StackTraceBuilder();
  }

  private static class StackTraceBuilder {

    private String className = "mock";
    private String methodName = "mock";
    private String fileName = "mock";
    private int lineNumber;

    public StackTraceBuilder className(final String className) {
      this.className = className;
      return this;
    }

    public StackTraceBuilder methodName(final String methodName) {
      this.methodName = methodName;
      return this;
    }

    public StackTraceBuilder fileName(final String fileName) {
      this.fileName = fileName;
      return this;
    }

    public StackTraceBuilder lineNumber(final int lineNumber) {
      this.lineNumber = lineNumber;
      return this;
    }

    public StackTraceElement build() {
      return new StackTraceElement(className, methodName, fileName, lineNumber);
    }
  }
}
