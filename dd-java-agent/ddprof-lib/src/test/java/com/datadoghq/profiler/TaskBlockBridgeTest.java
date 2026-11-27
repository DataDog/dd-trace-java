// Copyright 2026 Datadog, Inc.
package com.datadoghq.profiler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.libs.ddprof.DdprofLibraryLoader;
import org.junit.jupiter.api.Test;

class TaskBlockBridgeTest {
  @Test
  void resolvesPackagePrivateHooksFromProfilerPackage() {
    assertNotNull(TaskBlockBridge.findVirtual(SupportedHooks.class, "parkEnter", boolean.class));
    assertNotNull(TaskBlockBridge.findVirtual(SupportedHooks.class, "beginTaskBlock", long.class));
  }

  @Test
  void resolvesRealHooksFromPinnedProfilerArtifact() {
    Throwable reasonNotLoaded = DdprofLibraryLoader.javaProfiler().getReasonNotLoaded();
    assumeTrue(reasonNotLoaded == null, "Profiler native library not available");
    JavaProfiler profiler = DdprofLibraryLoader.javaProfiler().getComponent();
    assumeTrue(profiler != null, "Profiler native library not available");

    TaskBlockBridge bridge =
        new TaskBlockBridge(profiler, reason -> fail("Unexpected TaskBlock report: " + reason));
    assertTrue(bridge.hasParkSupport());
    assertTrue(bridge.hasSynchronousTaskBlockSupport());
    bridge.reportIfUnsupported();
    assertTrue(bridge.parkEnter());
    try {
      assertFalse(bridge.parkEnter());
    } finally {
      bridge.parkExit(0L, 0L);
    }
    assertTrue(bridge.parkEnter());
    bridge.parkExit(0L, 0L);
  }

  @Test
  void missingHooksAreReportedWithoutFailingClassInitialization() {
    assertNull(TaskBlockBridge.findVirtual(LegacyHooks.class, "parkEnter", boolean.class));
    assertNull(TaskBlockBridge.findVirtual(UnsupportedHooks.class, "parkEnter", boolean.class));
    assertNull(TaskBlockBridge.findVirtual(UnsupportedHooks.class, "beginTaskBlock", long.class));
  }

  static final class SupportedHooks {
    boolean parkEnter() {
      return true;
    }

    long beginTaskBlock() {
      return 1L;
    }
  }

  static final class LegacyHooks {
    void parkEnter() {}
  }

  static final class UnsupportedHooks {}
}
