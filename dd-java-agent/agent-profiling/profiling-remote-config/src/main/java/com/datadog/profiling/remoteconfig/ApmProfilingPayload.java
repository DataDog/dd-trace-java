package com.datadog.profiling.remoteconfig;

import datadog.json.JsonMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads an {@code APM_PROFILING} payload once, for both its profilers and its targets. */
final class ApmProfilingPayload {
  private static final Logger log = LoggerFactory.getLogger(ApmProfilingPayload.class);

  private ApmProfilingPayload() {}

  /**
   * @param problems receives {@code payload is not an object} when it isn't
   * @return the members of the payload, or null if it is unreadable or not a single object, e.g.
   *     one followed by other content
   */
  static Map<String, Object> read(String json, List<String> problems) {
    // the mapper takes these for an empty object, while they are not an object
    if (json != null && !json.isEmpty() && !"null".equals(json)) {
      try {
        return JsonMapper.fromJsonToMap(json);
      } catch (IOException e) {
        // invalid JSON. Any other exception is a bug, left to the poller to report
        log.debug("Ignoring an unreadable APM_PROFILING config: {}", e.toString());
      }
    }
    problems.add("payload is not an object");
    return null;
  }
}
