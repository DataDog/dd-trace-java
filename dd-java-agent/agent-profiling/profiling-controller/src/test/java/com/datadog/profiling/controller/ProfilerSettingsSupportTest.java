package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.profiling.controller.ProfilerSettingsSupport.ProfilerActivationSetting;
import com.datadog.profiling.controller.ProfilerSettingsSupport.ProfilerActivationSetting.Ssi;
import datadog.config.ConfigProvider;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.ProfilingEnablement;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class ProfilerSettingsSupportTest {
  @ParameterizedTest
  @MethodSource("activationSettings")
  void testActivation(
      String enabledSetting, String injectSetting, ProfilerActivationSetting expected) {
    Properties props = new Properties();
    if (enabledSetting != null) {
      props.put(ProfilingConfig.PROFILING_ENABLED, enabledSetting);
    }
    if (injectSetting != null) {
      props.put("injection.enabled", injectSetting);
    }
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    ProfilerActivationSetting setting =
        ProfilerSettingsSupport.getProfilerActivation(configProvider);
    assertEquals(expected, setting);
  }

  private static Stream<Arguments> activationSettings() {
    return Stream.of(
        Arguments.of(
            "true", null, new ProfilerActivationSetting(ProfilingEnablement.ENABLED, Ssi.NONE)),
        Arguments.of(
            "true",
            "tracer",
            new ProfilerActivationSetting(ProfilingEnablement.ENABLED, Ssi.INJECTED_AGENT)),
        Arguments.of(
            "auto", null, new ProfilerActivationSetting(ProfilingEnablement.AUTO, Ssi.NONE)),
        Arguments.of(
            "auto",
            "tracer",
            new ProfilerActivationSetting(ProfilingEnablement.AUTO, Ssi.INJECTED_AGENT)),
        Arguments.of(
            null,
            "profiler,tracer",
            new ProfilerActivationSetting(ProfilingEnablement.INJECTED, Ssi.INJECTED_AGENT)),
        Arguments.of(
            "true",
            "profiler,tracer",
            new ProfilerActivationSetting(ProfilingEnablement.ENABLED, Ssi.INJECTED_AGENT)),
        Arguments.of(
            "auto",
            "profiler,tracer",
            new ProfilerActivationSetting(ProfilingEnablement.AUTO, Ssi.INJECTED_AGENT)));
  }
}
