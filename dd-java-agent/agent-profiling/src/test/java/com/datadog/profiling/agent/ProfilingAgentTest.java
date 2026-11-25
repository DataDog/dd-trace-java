package com.datadog.profiling.agent;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.datadog.profiling.remoteconfig.ProfilingConfigPoller;
import datadog.trace.api.Config;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProfilingAgentTest {
  /**
   * Profiling remote config only applies to armed profiling: otherwise, subscribing to it doesn't
   * wait for the lock that the profiler initialization holds, which would hold up the remote config
   * of every other product.
   */
  @Test
  void startRemoteConfigDoesNotWaitForTheProfilerInitializationUnlessArmed() {
    assumeFalse(ProfilingConfigPoller.isEnabled(Config.get()), "profiling is armed");

    // as if the profiler were being initialized
    synchronized (ProfilingAgent.class) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(5), () -> ProfilingAgent.startRemoteConfig(null));
    }
  }
}
