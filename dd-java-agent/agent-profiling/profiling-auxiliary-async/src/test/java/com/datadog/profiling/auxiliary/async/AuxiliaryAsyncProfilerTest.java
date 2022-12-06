package com.datadog.profiling.auxiliary.async;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AuxiliaryAsyncProfilerTest {
  private static final Logger log = LoggerFactory.getLogger(AuxiliaryAsyncProfilerTest.class);

  @Test
  void testProvider() {
    AuxiliaryAsyncProfiler.ImplementerProvider provider =
        new AuxiliaryAsyncProfiler.ImplementerProvider();
    assertTrue(provider.canProvide(AuxiliaryAsyncProfiler.TYPE));
    assertFalse(provider.canProvide(null));
    assertFalse(provider.canProvide(""));
    assertFalse(provider.canProvide("unknown"));

    assertNotNull(provider.provide(ConfigProvider.getInstance()));
  }
}
