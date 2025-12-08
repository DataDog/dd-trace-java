package datadog.trace.util.stacktrace;

import static datadog.trace.util.stacktrace.StackWalkerTestUtil.DD_CLASS_NAME;
import static datadog.trace.util.stacktrace.StackWalkerTestUtil.DD_IAST_CLASS_NAME;
import static datadog.trace.util.stacktrace.StackWalkerTestUtil.NOT_DD_CLASS_NAME;
import static datadog.trace.util.stacktrace.StackWalkerTestUtil.getStackWalkFrom;
import static datadog.trace.util.stacktrace.StackWalkerTestUtil.isRunningJDK8WithHotSpot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.environment.JavaVirtualMachine;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class HotSpotStackWalkerTest {

  private final StackWalker stackWalker = new HotSpotStackWalker();

  @BeforeAll
  public static void setUp() {
    assumeTrue(isRunningJDK8WithHotSpot());
    assumeFalse(
        JavaVirtualMachine.isOracleJDK8(),
        "Oracle JDK 1.8 did not merge the fix in JDK-8058322, leading to the JVM failing to correctly "
            + "extract method parameters without args, when the code is compiled on a later JDK (targeting 8). "
            + "This can manifest when creating mocks.");
  }

  @Test
  public void hotSpotStackWalker_must_be_enabled_for_JDK8_with_hotspot() {
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

  @Test
  public void is_not_enabled_when_access_throws_exception() {
    // When
    sun.misc.JavaLangAccess mockedAccess = mock(sun.misc.JavaLangAccess.class);
    when(mockedAccess.getStackTraceElement(any(Throwable.class), eq(0)))
        .thenThrow(new RuntimeException());
    HotSpotStackWalker hotSpotStackWalker = new HotSpotStackWalker();
    hotSpotStackWalker.access = mockedAccess;
    // Then
    assertFalse(hotSpotStackWalker.isEnabled());
  }

  @Test
  public void is_not_enabled_when_access_is_null() {
    // When
    HotSpotStackWalker hotSpotStackWalker = new HotSpotStackWalker();
    hotSpotStackWalker.access = null;
    // Then
    assertFalse(hotSpotStackWalker.isEnabled());
  }

  @Test
  public void iterable_from_HotSpotStackWalker_doGetStack_is_not_in_the_filtered_stack() {
    // When
    List<StackTraceElement> list = stackWalker.walk(s -> s.collect(Collectors.toList()));
    // Then
    assertFalse(
        list.stream()
            .anyMatch(
                stackTraceElement ->
                    stackTraceElement
                        .toString()
                        .equals("java.lang.Iterable.spliterator(Iterable.java:101)")));
  }
}
