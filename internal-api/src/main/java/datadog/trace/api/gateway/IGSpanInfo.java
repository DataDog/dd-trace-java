package datadog.trace.api.gateway;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface IGSpanInfo {
  DDTraceId getTraceId();

  long getSpanId();

  TagMap getTags();

  AgentSpan setTag(String key, boolean value);

  void setRequestBlockingAction(Flow.Action.RequestBlockingAction rba);

  Flow.Action.RequestBlockingAction getRequestBlockingAction();
}
