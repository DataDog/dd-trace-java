package com.datadog.appsec.ddwaf;

import com.datadog.appsec.config.TraceSegmentPostProcessor;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.AppSecEvent;
import datadog.trace.api.internal.TraceSegment;
import java.util.Collection;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post processor that serializes trace attributes from the AppSec request context to the trace
 * segment during trace post-processing.
 *
 * <p>This processor handles the new trace tagging feature where WAF rules can specify attributes to
 * be added to the trace segment.
 */
public class TraceTaggingPostProcessor implements TraceSegmentPostProcessor {
  private static final Logger log = LoggerFactory.getLogger(TraceTaggingPostProcessor.class);

  @Override
  public void processTraceSegment(
      TraceSegment segment, AppSecRequestContext ctx, Collection<AppSecEvent> collectedEvents) {

    Map<String, Object> traceAttributes = ctx.getTraceAttributes();
    if (traceAttributes == null || traceAttributes.isEmpty()) {
      return;
    }

    log.debug("Serializing {} trace attributes to trace segment", traceAttributes.size());

    // Serialize each attribute to the trace segment
    for (Map.Entry<String, Object> entry : traceAttributes.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();

      if (key != null && !key.isEmpty() && value != null) {
        try {
          // Use setTagTop to add the attribute to the trace segment
          segment.setTagTop(key, value);
          log.debug("Added trace attribute: {} = {}", key, value);
        } catch (Exception e) {
          log.warn("Failed to serialize trace attribute {} = {}", key, value, e);
        }
      } else {
        log.debug("Skipping invalid trace attribute: key='{}', value='{}'", key, value);
      }
    }
  }
}
