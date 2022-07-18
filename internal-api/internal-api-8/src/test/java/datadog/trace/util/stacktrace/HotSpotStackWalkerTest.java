package datadog.trace.util.stacktrace;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class HotSpotStackWalkerTest {

  private final HotSpotStackWalker hotSpotStackWalker = new HotSpotStackWalker();

  @Test
  public void hotSpotStackWalker_must_be_enabled_for_JDK8_with_hotspot() {
    assertTrue(hotSpotStackWalker.isEnabled());
  }

  @Test
  public void get_stack_trace() {
    // When
    Stream<StackTraceElement> stream = hotSpotStackWalker.doGetStack();
    // Then
    assertNotEquals(stream.count(), 0);
  }
}
