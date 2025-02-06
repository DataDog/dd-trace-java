package datadog.trace.instrumentation.aws.v2.sfn;

import datadog.json.JsonMapper;
import datadog.json.JsonWriter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class InputAttributeInjector {
  private static final String DATADOG_KEY = "_datadog";

  public static String buildTraceContext(AgentSpan span) {
    String tagsJson = JsonMapper.toJson(span.getTags());
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject();
      writer.name("x-datadog-trace-id").value(span.getTraceId().toString());
      writer.name("x-datadog-parent-id").value(String.valueOf(span.getSpanId()));
      writer.name("x-datadog-tags").jsonValue(tagsJson);
      writer.endObject();
      return writer.toString();
    } catch (Exception e) {
      return "{}";
    }
  }

  public static String getModifiedInput(String request, String ddTraceContextJSON) {
    StringBuilder modifiedInput = new StringBuilder(request.trim());
    int startPos = modifiedInput.indexOf("{");
    int endPos = modifiedInput.lastIndexOf("}");

    String inputContent = modifiedInput.substring(startPos + 1, endPos).trim();
    if (inputContent.isEmpty()) {
      modifiedInput.insert(endPos, String.format("\"%s\":%s", DATADOG_KEY, ddTraceContextJSON));
    } else {
      // Prepend comma to separate from existing content
      modifiedInput.insert(endPos, String.format(",\"%s\":%s", DATADOG_KEY, ddTraceContextJSON));
    }
    return modifiedInput.toString();
  }
}
