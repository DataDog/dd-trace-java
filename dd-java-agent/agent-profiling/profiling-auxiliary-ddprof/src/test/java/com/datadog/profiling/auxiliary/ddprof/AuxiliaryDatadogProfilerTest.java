package com.datadog.profiling.auxiliary.ddprof;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import org.junit.jupiter.api.Test;

class AuxiliaryDatadogProfilerTest {

  @Test
  void testProvider() {
    AuxiliaryDatadogProfiler.ImplementerProvider provider =
        new AuxiliaryDatadogProfiler.ImplementerProvider();
    assertTrue(provider.canProvide(AuxiliaryDatadogProfiler.TYPE));
    assertFalse(provider.canProvide(null));
    assertFalse(provider.canProvide(""));
    assertFalse(provider.canProvide("unknown"));

    assertNotNull(provider.provide(ConfigProvider.getInstance()));
  }
}
