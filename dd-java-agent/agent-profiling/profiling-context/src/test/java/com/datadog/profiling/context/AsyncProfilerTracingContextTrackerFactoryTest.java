package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class AsyncProfilerTracingContextTrackerFactoryTest {

  @Test
  void testTracingAvailable() {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_ASYNC_ENABLED, Boolean.toString(true));
    props.put(ProfilingConfig.PROFILING_ASYNC_WALL_ENABLED, Boolean.toString(true));
    props.put(ProfilingConfig.PROFILING_ASYNC_WALL_FILTER_ON_CONTEXT, Boolean.toString(true));
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    assertTrue(AsyncProfilerTracingContextTrackerFactory.isEnabled(configProvider));

    AsyncProfilerTracingContextTrackerFactory instance =
        new AsyncProfilerTracingContextTrackerFactory();

    TracingContextTracker tracker = instance.instance(null);
    assertNotNull(tracker);
    assertNotEquals(tracker, TracingContextTracker.EMPTY);
  }
}
