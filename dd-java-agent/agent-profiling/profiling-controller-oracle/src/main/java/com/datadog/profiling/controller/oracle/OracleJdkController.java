package com.datadog.profiling.controller.oracle;

import com.datadog.profiling.controller.ConfigurationException;
import com.datadog.profiling.controller.Controller;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.controller.jfr.JfpUtils;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the implementation of the controller for Oracle JDK. It will work only for JDK 8 built
 * and distributed by Oracle.
 */
public final class OracleJdkController implements Controller {
  private static final Logger log = LoggerFactory.getLogger(OracleJdkController.class);
  static final int RECORDING_MAX_SIZE = 64 * 1024 * 1024; // 64 megs
  static final Duration RECORDING_MAX_AGE = Duration.ofMinutes(5);

  private final Map<String, String> eventSettings;
  private final JfrMBeanHelper helper;

  /**
   * Main constructor for Oracle JDK profiling controller.
   *
   * <p>This has to be public because it is created via reflection
   */
  public OracleJdkController(@Nonnull final ConfigProvider configProvider)
      throws ConfigurationException {
    try {
      log.debug("Initializing Oracle JFR controller");
      helper = new JfrMBeanHelper();
      eventSettings =
          Collections.unmodifiableMap(
              JfpUtils.readJfpResources(
                  JfpUtils.DEFAULT_JFP,
                  configProvider.getString(ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE)));
    } catch (final IOException e) {
      throw new ConfigurationException(e);
    }
  }

  @Override
  @Nonnull
  public OracleJdkOngoingRecording createRecording(@Nonnull final String recordingName)
      throws UnsupportedEnvironmentException {
    try {
      log.debug("Attempting to create a new recording with name '{}'", recordingName);
      return new OracleJdkOngoingRecording(
          helper, recordingName, RECORDING_MAX_SIZE, RECORDING_MAX_AGE, eventSettings);
    } catch (final IOException e) {
      throw new RuntimeException("Unable to create a new recording with name " + recordingName, e);
    }
  }
}
