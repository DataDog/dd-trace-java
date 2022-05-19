package com.datadog.profiling.controller.openj9;

import static com.datadog.profiling.controller.ProfilingSupport.*;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AUXILIARY_TYPE;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.controller.RecordingData;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpenJ9ControllerTest {

  private static final String TEST_NAME = "recording name";

  @Test
  public void testCreateContinuousRecording() throws Exception {
    Properties props = new Properties();
    props.put(PROFILING_AUXILIARY_TYPE, "async");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    OpenJ9Controller controller = new OpenJ9Controller(configProvider);
    RecordingData data = controller.createRecording(TEST_NAME).stop();
    assertEquals("async-profiler", data.getName());
  }
}
