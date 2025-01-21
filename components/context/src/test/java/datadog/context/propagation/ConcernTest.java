package datadog.context.propagation;

import static datadog.context.propagation.Concern.DEFAULT_PRIORITY;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConcernTest {
  @Test
  void testNamed() {
    assertThrows(
        NullPointerException.class,
        () -> Concern.named(null),
        "Should not create null named concern");
    assertNotNull(Concern.named("name"));
  }

  @Test
  void testWithPriority() {
    assertThrows(
        NullPointerException.class,
        () -> Concern.withPriority(null, DEFAULT_PRIORITY),
        "Should not create null named concern");
    assertThrows(
        IllegalArgumentException.class,
        () -> Concern.withPriority("name", -1),
        "Should not create negative priority concern");
    assertNotNull(Concern.withPriority("high-priority", DEFAULT_PRIORITY - 10));
    assertNotNull(Concern.withPriority("low-priority", DEFAULT_PRIORITY + 10));
  }

  @Test
  void testName() {
    String debugName = "name";
    Concern concern = Concern.named(debugName);
    assertEquals(debugName, concern.toString(), "Concern name mismatch");
  }
}
