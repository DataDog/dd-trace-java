package com.datadog.profiling.remoteconfig;

import com.squareup.moshi.JsonReader;
import java.util.List;
import java.util.Map;
import okio.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads an {@code APM_PROFILING} payload once, for both its profilers and its targets. */
final class ApmProfilingPayload {
  private static final Logger log = LoggerFactory.getLogger(ApmProfilingPayload.class);

  private ApmProfilingPayload() {}

  /**
   * @param problems receives {@code payload is not an object} when it isn't
   * @return the members of the payload, or null if it is unreadable or not an object
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> read(String json, List<String> problems) {
    try {
      Object value = JsonReader.of(new Buffer().writeUtf8(json)).readJsonValue();
      if (value instanceof Map) {
        return (Map<String, Object>) value;
      }
    } catch (Exception e) {
      log.debug("Ignoring an unreadable APM_PROFILING config: {}", e.toString());
    }
    problems.add("payload is not an object");
    return null;
  }
}
