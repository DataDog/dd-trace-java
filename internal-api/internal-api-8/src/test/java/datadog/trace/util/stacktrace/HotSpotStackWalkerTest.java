package datadog.trace.util.stacktrace;

import static datadog.trace.util.stacktrace.StackWalkerTestUtil.isRunningJDK8WithHotSpot;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class HotSpotStackWalkerTest {

  @BeforeAll
  public static void setUp() {
    assumeTrue(isRunningJDK8WithHotSpot());
  }

  @Test
  public void hotSpotStackWalker_must_be_enabled_for_JDK8_with_hotspot() {
    assertTrue(new HotSpotStackWalker().isEnabled());
  }

  @Test
  public void get_stack_trace() {
    // When
    List<StackTraceElement> list = new HotSpotStackWalker().doGetStack(StackWalkerTestUtil::toList);
    // Then
    assertFalse(list.isEmpty());
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
    List<StackTraceElement> list = new HotSpotStackWalker().doGetStack(StackWalkerTestUtil::toList);
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
