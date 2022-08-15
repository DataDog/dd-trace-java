package datadog.trace.util.stacktrace;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class StackWalkerFactoryTest {

  @Test
  public void stackWalker_instance_must_be_enabled() {
    // When
    StackWalker stackWalker = StackWalkerFactory.INSTANCE;
    // Then
    assertTrue(stackWalker.isEnabled());
  }
}
