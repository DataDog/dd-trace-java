package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import datadog.trace.bootstrap.appsec.sca.ScaReachabilityCallback;
import java.lang.instrument.Instrumentation;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScaReachabilitySystemTest {

  @AfterEach
  void tearDown() {
    ScaReachabilityDependencyRegistry.INSTANCE.resetForTesting();
    ScaReachabilityCallback.register(null);
  }

  @Test
  void startRegistersTransformerCallbackAndPeriodicWork() {
    Instrumentation instrumentation = mock(Instrumentation.class);
    when(instrumentation.getAllLoadedClasses()).thenReturn(new Class<?>[0]);

    ScaReachabilitySystem.start(instrumentation);

    verify(instrumentation).addTransformer(any(ScaReachabilityTransformer.class), eq(true));
    assertNotNull(ScaReachabilityDependencyRegistry.INSTANCE.getPeriodicWorkCallback());

    ScaReachabilityCallback.onMethodHit(
        "GHSA-start", "com.example:lib", "1.0.0", "missing.Vulnerable", "danger", 42);

    List<ScaReachabilityDependencyRegistry.DependencySnapshot> snapshots =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();
    assertEquals(1, snapshots.size());
    assertEquals("com.example:lib", snapshots.get(0).artifact);
    assertEquals("1.0.0", snapshots.get(0).version);
    assertEquals(1, snapshots.get(0).cves.size());
    ScaReachabilityHit hit = snapshots.get(0).cves.get(0).hit;
    assertNotNull(hit);
    assertEquals("GHSA-start", hit.vulnId());
    assertEquals("missing.Vulnerable", hit.className());
    assertEquals("danger", hit.symbolName());
    assertEquals(42, hit.line());
  }
}
