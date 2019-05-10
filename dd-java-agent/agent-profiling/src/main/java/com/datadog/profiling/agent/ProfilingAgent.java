package com.datadog.profiling.agent;

import com.datadog.profiling.controller.BadConfigurationException;
import com.datadog.profiling.controller.ProfilingSystem;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import com.datadog.profiling.uploader.ChunkUploader;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Simple agent wrapper for starting the profiling agent from the command-line, without requiring
 * the APM agent. This makes it possible to run the profiling agent stand-alone. Of course, this
 * also means no contextual events from the tracing will be present.
 */
@Slf4j
public class ProfilingAgent {
  private static final String ENV_VAR_DURATION = "DD_PROFILE_DURATION_SEC";
  private static final String ENV_VAR_PERIOD = "DD_PROFILE_PERIOD_SEC";
  private static final String ENV_VAR_DELAY = "DD_PROFILE_DELAY_SEC";
  private static final String ENV_VAR_URL = "DD_PROFILE_ENDPOINT";
  private static final String ENV_VAR_API_KEY = "DD_PROFILE_API_KEY";
  private static final String ENV_VAR_TAGS = "DD_PROFILE_TAGS"; // comma separated, no spaces

  private static final int DEFAULT_DURATION = 60;
  private static final int DEFAULT_PERIOD = 3600;
  private static final int DEFAULT_DELAY = 30;
  private static final String DEFAULT_URL =
      "http://localhost:5000/api/v0/profiling/jfk-chunk"; // TODO our eventual prod endpoint

  // Overkill to make these volatile?
  private static ProfilingSystem profiler;
  private static ChunkUploader uploader;

  public static synchronized void run() throws IllegalArgumentException {
    if (profiler == null) {
      final String apiKey = System.getenv(ENV_VAR_API_KEY);
      if (apiKey == null) {
        throw new IllegalArgumentException("You must set env var " + ENV_VAR_API_KEY);
      }
      uploader = new ChunkUploader(getString(ENV_VAR_URL, DEFAULT_URL), apiKey, getTags());
      try {
        profiler =
            new ProfilingSystem(
                uploader.getRecordingDataListener(),
                Duration.ofSeconds(getInt(ENV_VAR_DELAY, DEFAULT_DELAY)),
                Duration.ofSeconds(getInt(ENV_VAR_PERIOD, DEFAULT_PERIOD)),
                Duration.ofSeconds(getInt(ENV_VAR_DURATION, DEFAULT_DURATION)));
        profiler.start();
      } catch (final UnsupportedEnvironmentException | IOException | BadConfigurationException e) {
        log.warn("Failed to initialize profiling agent!", e);
      }
    }
  }

  private static String[] getTags() {
    // TODO allow a passed `host` tag to override the default one
    String hostTag;
    try {
      hostTag = "host:" + InetAddress.getLocalHost().getHostName();
    } catch (final java.net.UnknownHostException e) {
      hostTag = "host:unknown";
    }
    String envVarTags = getString(ENV_VAR_TAGS, "");
    if (envVarTags.length() == 0) {
      envVarTags = hostTag;
    } else {
      envVarTags += "," + hostTag;
    }
    return envVarTags.split(",");
  }

  private static int getInt(final String key, final int defaultValue) {
    final String val = System.getenv(key);
    if (val != null) {
      try {
        return Integer.valueOf(val);
      } catch (final NumberFormatException e) {
        log.warn("Could not parse key {}. Will go with default {}.", key, defaultValue);
        return defaultValue;
      }
    }
    log.info("Could not find key {}. Will go with default {}.", key, defaultValue);
    return defaultValue;
  }

  private static String getString(final String key, final String defaultValue) {
    final String val = System.getenv(key);
    if (val == null) {
      log.info("Could not find key {}. Will go with default {}.", key, defaultValue);
      return defaultValue;
    }
    return val;
  }
}
