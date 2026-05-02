package com.datadog.profiling.scrubber;

import io.jafar.tools.Scrubber;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provides the default scrub definition targeting sensitive JFR event fields. */
public final class DefaultScrubDefinition {

  private static final Map<String, Scrubber.ScrubField> DEFAULT_SCRUB_FIELDS;

  static {
    Map<String, Scrubber.ScrubField> fields = new HashMap<>();
    // ScrubField(keyField, valueField, predicate): null keyField = scrub all values unconditionally
    // System properties may contain API keys, passwords
    fields.put("jdk.InitialSystemProperty", new Scrubber.ScrubField(null, "value", (k, v) -> true));
    // JVM args may contain credentials in -D flags
    fields.put("jdk.JVMInformation", new Scrubber.ScrubField(null, "jvmArguments", (k, v) -> true));
    // Env vars may contain secrets
    fields.put(
        "jdk.InitialEnvironmentVariable", new Scrubber.ScrubField(null, "value", (k, v) -> true));
    // Process command lines may reveal infrastructure
    fields.put("jdk.SystemProcess", new Scrubber.ScrubField(null, "commandLine", (k, v) -> true));
    DEFAULT_SCRUB_FIELDS = Collections.unmodifiableMap(fields);
  }

  /**
   * Creates a scrubber with the default scrub definition.
   *
   * @param excludeEventTypes list of event type names to exclude from scrubbing, or null for none
   * @return a configured {@link JfrScrubber}
   */
  public static JfrScrubber create(List<String> excludeEventTypes) {
    Set<String> excludeSet =
        excludeEventTypes != null
            ? new HashSet<>(excludeEventTypes)
            : Collections.<String>emptySet();

    return new JfrScrubber(
        eventTypeName -> {
          if (excludeSet.contains(eventTypeName)) {
            return null;
          }
          return DEFAULT_SCRUB_FIELDS.get(eventTypeName);
        });
  }

  private DefaultScrubDefinition() {}
}
