package com.datadog.profiling.controller;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datadog.profiling.agent.CompositeController;
import com.datadog.profiling.controller.openjdk.OpenJdkController;
import datadog.config.ConfigProvider;
import java.util.Properties;
import org.junit.jupiter.api.Test;

public class InstantiationTest {

  /**
   * We assume that tests for this module are run only on JVMs that support JFR. Ideally we would
   * want to have a conditional annotation to this, but currently it is somewhat hard to do well,
   * partially because jfr is available in some java8 versions and not others. Currently we just run
   * tests with java11 that is guaranteed to have JFR.
   */
  @Test
  public void testCreateController() throws UnsupportedEnvironmentException {
    Controller controller =
        CompositeController.build(ConfigProvider.getInstance(), new ControllerContext());
    if (controller instanceof CompositeController) {
      assertEquals(
          1,
          ((CompositeController) controller)
              .getControllers().stream().filter(c -> c instanceof OpenJdkController).count());
    } else {
      assertInstanceOf(OpenJdkController.class, controller);
    }
  }

  @Test
  public void testConfigurationException() {
    Properties props = new Properties();
    props.put(PROFILING_TEMPLATE_OVERRIDE_FILE, "some-path-that-is-not-supposed-to-exist!!!");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertThrows(ConfigurationException.class, () -> OpenJdkController.instance(configProvider));
  }
}
