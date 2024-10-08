package datadog.trace.instrumentation.aws.v2.sfn;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class InputAttributeInjector {
  public static String buildTraceContext(AgentSpan span) {
    // Extract span tags
    StringBuilder spanTagsJSON = new StringBuilder();
    spanTagsJSON.append('{');
    span.getTags()
        .forEach(
            (tagKey, tagValue) ->
                spanTagsJSON
                    .append('"')
                    .append(tagKey)
                    .append("\":\"")
                    .append(tagValue)
                    .append("\","));
    spanTagsJSON.setLength(spanTagsJSON.length() - 1); // remove trailing comma
    spanTagsJSON.append('}');

    // Build DD trace context object
    String ddTraceContextJSON =
        String.format(
            "\"_datadog\": { \"x-datadog-trace-id\": \"%s\",\"x-datadog-parent-id\":\"%s\", \"x-datadog-tags\": %s }",
            span.getTraceId().toString(), span.getSpanId(), spanTagsJSON);

    return ddTraceContextJSON;
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
