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
      return null;
    }
  }

  public static String getModifiedInput(String request, String ddTraceContextJSON) {
    if (request == null || ddTraceContextJSON == null) {
      return request; // leave request unmodified
    }

    final String traceContextProperty = "\"" + DATADOG_KEY + "\":" + ddTraceContextJSON;
    int startPos = request.indexOf('{');
    int endPos = request.lastIndexOf('}');

    if (startPos < 0 || endPos < startPos) {
      return request; // leave request unmodified
    }

    // If input is an empty {}
    if (endPos == startPos + 1) {
      return "{" + traceContextProperty + "}";
    }

    String existingJSON = request.substring(startPos + 1, endPos).trim();
    if (existingJSON.isEmpty()) {
      return "{" + traceContextProperty + "}";
    } else {
      return "{" + existingJSON + "," + traceContextProperty + "}";
    }
  }
}
