package com.datadog.profiling.scrubber;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_SCRUB_EXCLUDE_EVENTS;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Provides the default scrub definition targeting sensitive JFR event fields. */
public final class DefaultScrubDefinition {

  private static final Map<String, JfrScrubber.ScrubField> DEFAULT_SCRUB_FIELDS;

  static {
    Map<String, JfrScrubber.ScrubField> fields = new HashMap<>();
    // System properties may contain API keys, passwords
    fields.put(
        "jdk.InitialSystemProperty", new JfrScrubber.ScrubField(null, "value", (k, v) -> true));
    // JVM args may contain credentials in -D flags
    fields.put(
        "jdk.JVMInformation", new JfrScrubber.ScrubField(null, "jvmArguments", (k, v) -> true));
    // Env vars may contain secrets
    fields.put(
        "jdk.InitialEnvironmentVariable",
        new JfrScrubber.ScrubField(null, "value", (k, v) -> true));
    // Process command lines may reveal infrastructure
    fields.put(
        "jdk.SystemProcess", new JfrScrubber.ScrubField(null, "commandLine", (k, v) -> true));
    DEFAULT_SCRUB_FIELDS = Collections.unmodifiableMap(fields);
  }

  /**
   * Creates a scrub definition function that maps event type names to their scrub field
   * definitions. Event types listed in the {@link
   * datadog.trace.api.config.ProfilingConfig#PROFILING_SCRUB_EXCLUDE_EVENTS} configuration are
   * excluded from scrubbing.
   *
   * @param configProvider the configuration provider
   * @return a function mapping event type names to scrub field definitions
   */
  public static Function<String, JfrScrubber.ScrubField> create(ConfigProvider configProvider) {
    List<String> excludeList = configProvider.getList(PROFILING_SCRUB_EXCLUDE_EVENTS);
    Set<String> excludeSet =
        excludeList != null ? new HashSet<>(excludeList) : Collections.<String>emptySet();

    return eventTypeName -> {
      if (excludeSet.contains(eventTypeName)) {
        return null;
      }
      return DEFAULT_SCRUB_FIELDS.get(eventTypeName);
    };
  }

  private DefaultScrubDefinition() {}
}
