package datadog.trace.util.stacktrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.api.Platform;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import sun.misc.JavaLangAccess;
import sun.misc.SharedSecrets;

public class HotSpotStackWalkerTest {

  private final boolean isJDK8WithHotSpot = isRunningJDK8WithHotSpot();

  private final HotSpotStackWalker hotSpotStackWalker = new HotSpotStackWalker();

  @Test
  public void hotSpotStackWalker_must_be_enabled_for_JDK8_with_hotspot() {
    assertEquals(isJDK8WithHotSpot, hotSpotStackWalker.isEnabled());
  }

  @Test
  public void get_stack_trace() {
    assumeTrue(isJDK8WithHotSpot);
    // When
    Stream<StackTraceElement> stream = hotSpotStackWalker.doGetStack();
    // Then
    assertNotEquals(stream.count(), 0);
  }

  private boolean isRunningJDK8WithHotSpot() {
    try {
      JavaLangAccess access = SharedSecrets.getJavaLangAccess();
      return Platform.isJavaVersion(8) && access != null;
    } catch (Throwable throwable) {
      return false;
    }
  }
}
