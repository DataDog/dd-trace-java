package datadog.trace.instrumentation.langchain4j;

import datadog.trace.api.Config;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.jfr.llm.AiServiceEvent;
import datadog.trace.bootstrap.instrumentation.jfr.llm.ChatModelEvent;
import datadog.trace.bootstrap.instrumentation.jfr.llm.ToolExecutorEvent;
import datadog.trace.bootstrap.instrumentation.llm.LlmCallHandle;
import datadog.trace.bootstrap.instrumentation.llm.LlmObsHandle;

/** Creates {@link LlmObsHandle} instances for LangChain4j operation types. */
public final class LangChain4jLlmObsIntegration {

  public static final LangChain4jLlmObsIntegration INSTANCE = new LangChain4jLlmObsIntegration();

  private static final String INSTRUMENTATION_NAME = "langchain4j";
  private static final CharSequence OPERATION_CHAT_MODEL =
      UTF8BytesString.create("langchain4j.chat_model.request");
  private static final CharSequence OPERATION_AI_SERVICE =
      UTF8BytesString.create("langchain4j.ai_service.request");
  private static final CharSequence OPERATION_TOOL_EXECUTOR =
      UTF8BytesString.create("langchain4j.tool_executor.request");

  private LangChain4jLlmObsIntegration() {}

  public LlmObsHandle startLlm(String modelId) {
    ChatModelEvent jfrEvent = new ChatModelEvent(modelId);
    boolean jfrActive = jfrEvent.isEnabled();
    boolean obsActive = LLMObs.isEnabled();
    AgentScope agentScope = startApmSpan(OPERATION_CHAT_MODEL, modelId, Tags.SPAN_KIND_CLIENT);
    if (!jfrActive && !obsActive && agentScope == null) return LlmObsHandle.NOOP;
    try {
      LLMObsSpan obsSpan =
          obsActive ? LLMObs.startLLMSpan(modelId, modelId, null, null, null) : null;
      setJfrSpanContext(jfrEvent, jfrActive, obsSpan, agentScope);
      return new LlmCallHandle(jfrActive ? jfrEvent : null, obsSpan, agentScope);
    } catch (Throwable t) {
      abortApmSpan(agentScope);
      return LlmObsHandle.NOOP;
    }
  }

  public LlmObsHandle startWorkflow(String serviceType, String methodName) {
    AiServiceEvent jfrEvent = new AiServiceEvent(serviceType, methodName);
    boolean jfrActive = jfrEvent.isEnabled();
    boolean obsActive = LLMObs.isEnabled();
    String resource = serviceType + "." + methodName;
    AgentScope agentScope = startApmSpan(OPERATION_AI_SERVICE, resource, Tags.SPAN_KIND_INTERNAL);
    if (!jfrActive && !obsActive && agentScope == null) return LlmObsHandle.NOOP;
    try {
      LLMObsSpan obsSpan = obsActive ? LLMObs.startWorkflowSpan(resource, null, null) : null;
      setJfrSpanContext(jfrEvent, jfrActive, obsSpan, agentScope);
      return new LlmCallHandle(jfrActive ? jfrEvent : null, obsSpan, agentScope);
    } catch (Throwable t) {
      abortApmSpan(agentScope);
      return LlmObsHandle.NOOP;
    }
  }

  public LlmObsHandle startTool(String toolName) {
    ToolExecutorEvent jfrEvent = new ToolExecutorEvent(toolName);
    boolean jfrActive = jfrEvent.isEnabled();
    boolean obsActive = LLMObs.isEnabled();
    AgentScope agentScope =
        startApmSpan(OPERATION_TOOL_EXECUTOR, toolName, Tags.SPAN_KIND_INTERNAL);
    if (!jfrActive && !obsActive && agentScope == null) return LlmObsHandle.NOOP;
    try {
      LLMObsSpan obsSpan = obsActive ? LLMObs.startToolSpan(toolName, null, null) : null;
      setJfrSpanContext(jfrEvent, jfrActive, obsSpan, agentScope);
      return new LlmCallHandle(jfrActive ? jfrEvent : null, obsSpan, agentScope);
    } catch (Throwable t) {
      abortApmSpan(agentScope);
      return LlmObsHandle.NOOP;
    }
  }

  private static AgentScope startApmSpan(
      CharSequence operationName, String resourceName, String spanKind) {
    if (!AgentTracer.isRegistered() || !Config.get().isTraceEnabled()) return null;
    AgentSpan span = AgentTracer.startSpan(INSTRUMENTATION_NAME, operationName);
    span.setTag(Tags.COMPONENT, INSTRUMENTATION_NAME);
    span.setTag(Tags.SPAN_KIND, spanKind);
    span.setResourceName(resourceName != null ? resourceName : "unknown");
    return AgentTracer.activateSpan(span);
  }

  /** Correlates the JFR event with LLMObs span IDs, falling back to APM span IDs. */
  private static void setJfrSpanContext(
      jdk.jfr.Event jfrEvent, boolean jfrActive, LLMObsSpan obsSpan, AgentScope agentScope) {
    if (!jfrActive) return;
    if (obsSpan != null) {
      // Prefer LLMObs span for correlation so JFR and LLMObs traces join.
      if (jfrEvent instanceof AiServiceEvent) {
        ((AiServiceEvent) jfrEvent)
            .setSpanContext(obsSpan.getTraceId().toString(), Long.toHexString(obsSpan.getSpanId()));
      } else if (jfrEvent instanceof ChatModelEvent) {
        ((ChatModelEvent) jfrEvent)
            .setSpanContext(obsSpan.getTraceId().toString(), Long.toHexString(obsSpan.getSpanId()));
      } else if (jfrEvent instanceof ToolExecutorEvent) {
        ((ToolExecutorEvent) jfrEvent)
            .setSpanContext(obsSpan.getTraceId().toString(), Long.toHexString(obsSpan.getSpanId()));
      }
    } else if (agentScope != null) {
      // LLMObs disabled — fall back to APM span so JFR events still carry trace context.
      AgentSpan apmSpan = agentScope.span();
      String traceId = apmSpan.getTraceId().toString();
      String spanId = Long.toHexString(apmSpan.getSpanId());
      if (jfrEvent instanceof AiServiceEvent) {
        ((AiServiceEvent) jfrEvent).setSpanContext(traceId, spanId);
      } else if (jfrEvent instanceof ChatModelEvent) {
        ((ChatModelEvent) jfrEvent).setSpanContext(traceId, spanId);
      } else if (jfrEvent instanceof ToolExecutorEvent) {
        ((ToolExecutorEvent) jfrEvent).setSpanContext(traceId, spanId);
      }
    }
  }

  private static void abortApmSpan(AgentScope agentScope) {
    if (agentScope == null) return;
    agentScope.span().finish();
    agentScope.close();
  }
}
