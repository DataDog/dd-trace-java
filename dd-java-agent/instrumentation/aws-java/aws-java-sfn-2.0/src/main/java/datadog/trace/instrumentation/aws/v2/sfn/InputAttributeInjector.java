package datadog.trace.instrumentation.aws.v2.sfn;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;

import datadog.context.Context;
import datadog.json.JsonWriter;

public class InputAttributeInjector {
  private static final String DATADOG_KEY = "_datadog";

  public static String buildTraceContext(Context context) {
    if (fromContext(context) == null) {
      return null;
    }
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject();
      // note: injection allows non-datadog style propogation (W3C, B3)
      // which the extension does not yet extract
      defaultPropagator().inject(context, writer, TextMapInjectAdapter.SETTER);
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
