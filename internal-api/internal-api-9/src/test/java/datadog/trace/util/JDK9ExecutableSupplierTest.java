package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JDK9ExecutableSupplierTest {

  @Test
  void returnsCurrentProcessExecutable() {
    String expected = ProcessHandle.current().info().command().orElse(null);

    assertEquals(expected, new JDK9ExecutableSupplier().get());
  }
}
