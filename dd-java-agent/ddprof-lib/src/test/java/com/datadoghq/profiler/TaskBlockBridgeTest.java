// Copyright 2026 Datadog, Inc.
package com.datadoghq.profiler;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.libs.ddprof.DdprofLibraryLoader;
import org.junit.jupiter.api.Test;

class TaskBlockBridgeTest {
  @Test
  void resolvesPackagePrivateHooksFromProfilerPackage() {
    assertNotNull(TaskBlockBridge.findVirtual(SupportedHooks.class, "parkEnter", void.class));
    assertNotNull(TaskBlockBridge.findVirtual(SupportedHooks.class, "beginTaskBlock", long.class));
  }

  @Test
  void resolvesRealHooksFromPinnedProfilerArtifact() {
    Throwable reasonNotLoaded = DdprofLibraryLoader.javaProfiler().getReasonNotLoaded();
    assumeTrue(reasonNotLoaded == null, "Profiler native library not available");
    JavaProfiler profiler = DdprofLibraryLoader.javaProfiler().getComponent();
    assumeTrue(profiler != null, "Profiler native library not available");

    TaskBlockBridge bridge = new TaskBlockBridge(profiler);
    assertTrue(bridge.hasParkSupport());
    assertTrue(bridge.hasSynchronousTaskBlockSupport());
  }

  @Test
  void missingHooksAreReportedWithoutFailingClassInitialization() {
    assertNull(TaskBlockBridge.findVirtual(UnsupportedHooks.class, "parkEnter", void.class));
    assertNull(TaskBlockBridge.findVirtual(UnsupportedHooks.class, "beginTaskBlock", long.class));
  }

  static final class SupportedHooks {
    void parkEnter() {}

    long beginTaskBlock() {
      return 1L;
    }
  }

  static final class UnsupportedHooks {}
}
