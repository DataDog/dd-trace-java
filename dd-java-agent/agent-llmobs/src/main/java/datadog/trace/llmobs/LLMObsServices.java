package datadog.trace.llmobs;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.llmobs.domain.SpanContextInfo;
import java.util.Deque;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LLMObsServices {

  private static final Logger logger = LoggerFactory.getLogger(LLMObsServices.class);

  final Config config;
  final BackendApi backendApi;

  Map<DDTraceId, Deque<SpanContextInfo>> activeSpanContextByTID = new ConcurrentHashMap<>();

  LLMObsServices(Config config, SharedCommunicationObjects sco) {
    this.config = config;
    this.backendApi =
        new BackendApiFactory(config, sco).createBackendApi(BackendApiFactory.Intake.LLMOBS_API);
  }

  @Nonnull
  public SpanContextInfo getActiveSpanContext() {
    // Valid case: possibly start root llm obs span/trace while there is NOT an active apm trace
    AgentScope activeScope = AgentTracer.activeScope();
    if (activeScope == null) {
      return new SpanContextInfo();
    }

    // Unexpected case: null active scope span, log to avoid crashes
    if (activeScope.span() == null) {
      logger.warn("active span scope found but no null span");
      return new SpanContextInfo();
    }

    // Unexpected case: null trace ID, log to avoid crashes
    DDTraceId traceId = activeScope.span().getTraceId();
    if (traceId == null) {
      logger.warn("active scope found but unexpectedly null trace ID");
      return new SpanContextInfo();
    }

    Deque<SpanContextInfo> activeSpanCtxForTID = activeSpanContextByTID.get(traceId);
    // Valid case: possibly start root llm obs span/trace while there's an active apm trace
    if (activeSpanCtxForTID == null || activeSpanCtxForTID.isEmpty()) {
      return new SpanContextInfo();
    }

    // Valid case: possibly start child llm obs span for a given trace ID
    return activeSpanCtxForTID.peek();
  }

  public void setActiveSpanContext(SpanContextInfo spanContext) {
    AgentSpanContext activeCtx = spanContext.getActiveContext();
    if (activeCtx == null) {
      logger.warn("unexpected null active context");
      return;
    }

    DDTraceId traceId = activeCtx.getTraceId();
    if (traceId == null) {
      logger.warn("unexpected null trace ID");
      return;
    }

    Deque<SpanContextInfo> contexts = activeSpanContextByTID.get(activeCtx.getTraceId());
    if (contexts == null) {
      contexts = new ConcurrentLinkedDeque<>();
    }
    contexts.push(spanContext);
    this.activeSpanContextByTID.put(traceId, contexts);
  }

  public void removeActiveSpanContext(DDTraceId traceId) {
    if (!activeSpanContextByTID.containsKey(traceId)) {
      logger.debug("active span contexts not found for trace {}", traceId);
      return;
    }
    Deque<SpanContextInfo> contexts = activeSpanContextByTID.get(traceId);
    if (contexts == null) {
      return;
    }
    if (!contexts.isEmpty()) {
      try {
        contexts.pop();
        if (contexts.isEmpty()) {
          // the trace MAY still be active, however, the next set should re-create the hierarchy as
          // needed
          activeSpanContextByTID.remove(traceId);
        }
      } catch (NoSuchElementException noSuchElementException) {
        logger.debug("failed to pop context stack for trace {}", traceId);
      }
    }
  }
}
