package datadog.trace.lambda;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import java.util.LinkedHashMap;
import java.util.Map;

/** Event-derived data, independent of the lifetime of the invocation and its inferred spans. */
final class LambdaTrigger {
  String name;
  String service;
  String resource;
  String type = "web";
  long startMicros;
  boolean async;
  final Map<String, String> meta = new LinkedHashMap<>();
  final Map<String, String> tags = new LinkedHashMap<>();
  Map<String, String> carrier = new LinkedHashMap<>();
  AgentSpanContext generatedContext;
  LambdaTrigger wrapped;
  LambdaEventParser.LambdaTriggerType httpType = LambdaEventParser.LambdaTriggerType.UNKNOWN;

  LambdaTrigger(String source, String arn, boolean async) {
    this.async = async;
    if (source != null) {
      tags.put("function_trigger.event_source", source);
      tags.put("function_trigger.event_source_arn", arn);
    }
  }

  LambdaTrigger span(String name, String service, String resource, long startMicros) {
    this.name = name;
    this.service = service;
    this.resource = resource;
    this.startMicros = startMicros;
    return this;
  }
}
