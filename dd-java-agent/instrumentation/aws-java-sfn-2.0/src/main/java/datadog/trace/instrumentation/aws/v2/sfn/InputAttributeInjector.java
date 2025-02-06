package datadog.trace.instrumentation.aws.v2.sfn;

import datadog.json.JsonMapper;
import datadog.json.JsonWriter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class InputAttributeInjector {
  public static String buildTraceContext(AgentSpan span) {
    String tagsJson = JsonMapper.toJson(span.getTags());

    try {
      JsonWriter ddTraceContextJSON = new JsonWriter();
      ddTraceContextJSON
          .beginObject()
          .name("_datadog")
          .beginObject()
          .name("x-datadog-trace-id")
          .value(span.getTraceId().toString())
          .name("x-datadog-parent-id")
          .value(String.valueOf(span.getSpanId()))
          .name("x-datadog-tags")
          .jsonValue(tagsJson)
          .endObject()
          .endObject();

      return ddTraceContextJSON.toString();
    } catch (Exception e) {
      return "{}";
    }
  }

  public static String getModifiedInput(String request, String ddTraceContextJSON) {
    StringBuilder modifiedInput = new StringBuilder(request);
    int startPos = modifiedInput.indexOf("{");
    int endPos = modifiedInput.lastIndexOf("}");
    String inputContent = modifiedInput.substring(startPos + 1, endPos);
    if (inputContent.isEmpty()) {
      modifiedInput.insert(endPos, ddTraceContextJSON);
    } else {
      // Prepend comma to separate from existing content
      modifiedInput.insert(endPos, "," + ddTraceContextJSON);
    }
    return modifiedInput.toString();
  }
}
