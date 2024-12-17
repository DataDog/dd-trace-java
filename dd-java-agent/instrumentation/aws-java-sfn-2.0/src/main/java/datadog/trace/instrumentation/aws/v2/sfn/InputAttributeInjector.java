package datadog.trace.instrumentation.aws.v2.sfn;

import datadog.trace.bootstrap.JsonBuffer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class InputAttributeInjector {
  public static String buildTraceContext(AgentSpan span) {
    // Extract span tags
    JsonBuffer spanTagsJSON = new JsonBuffer();
    spanTagsJSON.beginObject();
    span.getTags()
        .forEach((tagKey, tagValue) -> spanTagsJSON.name(tagKey).value(tagValue.toString()));
    spanTagsJSON.endObject();

    // Build DD trace context object
    JsonBuffer ddTraceContextJSON = new JsonBuffer();
    ddTraceContextJSON
        .beginObject()
        .name("_datadog")
        .beginObject()
        .name("x-datadog-trace-id")
        .value(span.getTraceId().toString())
        .name("x-datadog-parent-id")
        .value(String.valueOf(span.getSpanId()))
        .name("x-datadog-tags")
        .value(spanTagsJSON)
        .endObject()
        .endObject();

    return ddTraceContextJSON.toString();
  }

  public static String getModifiedInput(String request, String ddTraceContextJSON) {
    StringBuilder modifiedInput = new StringBuilder(request);
    int startPos = modifiedInput.indexOf("{");
    int endPos = modifiedInput.lastIndexOf("}");
    String inputContent = modifiedInput.substring(startPos + 1, endPos);
    if (inputContent.isEmpty()) {
      modifiedInput.insert(endPos, ddTraceContextJSON);
    } else {
      modifiedInput.insert(
          endPos, ",".concat(ddTraceContextJSON)); // prepend comma to existing input
    }
    return modifiedInput.toString();
  }
}
