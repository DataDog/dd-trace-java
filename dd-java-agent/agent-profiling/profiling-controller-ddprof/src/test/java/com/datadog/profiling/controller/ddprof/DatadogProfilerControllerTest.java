package com.datadog.profiling.controller.ddprof;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_AUXILIARY_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.profiling.controller.ControllerContext;
import datadog.environment.OperatingSystem;
import datadog.trace.api.profiling.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import org.junit.Assume;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DatadogProfilerControllerTest {

  private static final String TEST_NAME = "recording name";

  @Test
  public void testCreateContinuousRecording() throws Exception {
    Assume.assumeTrue(OperatingSystem.isLinux());
    Properties props = new Properties();
    props.put(PROFILING_AUXILIARY_TYPE, "ddprof");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    DatadogProfilerController controller = new DatadogProfilerController(configProvider);
    RecordingData data =
        controller.createRecording(TEST_NAME, new ControllerContext().snapshot()).stop();
    assertEquals("ddprof", data.getName());
  }
}
