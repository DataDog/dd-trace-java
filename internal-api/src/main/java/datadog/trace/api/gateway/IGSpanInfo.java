package datadog.trace.api.gateway;

import datadog.trace.api.TagMap;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;

public interface IGSpanInfo {
  DDTraceId getTraceId();

  long getSpanId();

  TagMap getTags();

  AgentSpan setTag(String key, boolean value);

  void setRequestBlockingAction(Flow.Action.RequestBlockingAction rba);

  Flow.Action.RequestBlockingAction getRequestBlockingAction();

  boolean isRequiresPostProcessing();

  void setRequiresPostProcessing(boolean requiresPostProcessing);
}
