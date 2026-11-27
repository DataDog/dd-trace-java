package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JDK9PidSupplierTest {

  @Test
  void returnsCurrentProcessPid() {
    assertEquals(Long.toString(ProcessHandle.current().pid()), new JDK9PidSupplier().get());
  }
}
