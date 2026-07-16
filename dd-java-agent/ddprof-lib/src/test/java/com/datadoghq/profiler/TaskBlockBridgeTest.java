// Copyright 2026 Datadog, Inc.
package com.datadoghq.profiler;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TaskBlockBridgeTest {
  @Test
  void resolvesPackagePrivateHooksFromProfilerPackage() {
    assertNotNull(TaskBlockBridge.findVirtual(SupportedHooks.class, "parkEnter", void.class));
    assertNotNull(
        TaskBlockBridge.findVirtual(SupportedHooks.class, "beginTaskBlock", long.class, int.class));
  }

  @Test
  void missingHooksAreReportedWithoutFailingClassInitialization() {
    assertNull(TaskBlockBridge.findVirtual(UnsupportedHooks.class, "parkEnter", void.class));
    assertNull(
        TaskBlockBridge.findVirtual(
            UnsupportedHooks.class, "beginTaskBlock", long.class, int.class));
  }

  static final class SupportedHooks {
    void parkEnter() {}

    long beginTaskBlock(int state) {
      return state;
    }
  }

  static final class UnsupportedHooks {}
}
