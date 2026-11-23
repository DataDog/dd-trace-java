package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DebuggerInternalPackagesTest {

  @Test
  public void nullIsNotDebuggerInternal() {
    assertFalse(DebuggerInternalPackages.isDebuggerInternalClass(null));
  }

  @Test
  public void applicationClassIsNotDebuggerInternal() {
    assertFalse(DebuggerInternalPackages.isDebuggerInternalClass("com/example/app/MyClass"));
  }

  @Test
  public void debuggerInstrumentationPackageIsInternal() {
    assertTrue(
        DebuggerInternalPackages.isDebuggerInternalClass(
            "com/datadog/debugger/instrumentation/Types"));
  }

  @Test
  public void debuggerSymbolPackageIsInternal() {
    assertTrue(
        DebuggerInternalPackages.isDebuggerInternalClass(
            "com/datadog/debugger/symbol/SymbolAggregator"));
  }

  @Test
  public void debuggerPackageOutsideSkippedListIsNotInternal() {
    assertFalse(
        DebuggerInternalPackages.isDebuggerInternalClass("com/datadog/debugger/el/Something"));
  }
}
