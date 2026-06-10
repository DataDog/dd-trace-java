package datadog.trace.instrumentation.langchain4j;

import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.bootstrap.instrumentation.jfr.llm.AiServiceEvent;
import datadog.trace.bootstrap.instrumentation.jfr.llm.ChatModelEvent;
import datadog.trace.bootstrap.instrumentation.jfr.llm.ToolExecutorEvent;
import datadog.trace.bootstrap.instrumentation.llm.LlmCallHandle;
import datadog.trace.bootstrap.instrumentation.llm.LlmObsHandle;

/** Creates {@link LlmObsHandle} instances for LangChain4j operation types. */
public final class LangChain4jLlmObsIntegration {

  public static final LangChain4jLlmObsIntegration INSTANCE = new LangChain4jLlmObsIntegration();

  private LangChain4jLlmObsIntegration() {}

  public LlmObsHandle startLlm(String modelId) {
    ChatModelEvent jfrEvent = new ChatModelEvent(modelId);
    boolean jfrActive = jfrEvent.isEnabled();
    boolean obsActive = LLMObs.isEnabled();
    if (!jfrActive && !obsActive) return LlmObsHandle.NOOP;
    LLMObsSpan span = obsActive ? LLMObs.startLLMSpan(modelId, modelId, null, null, null) : null;
    if (jfrActive && span != null) {
      jfrEvent.setSpanContext(span.getTraceId().toString(), Long.toHexString(span.getSpanId()));
    }
    return new LlmCallHandle(jfrActive ? jfrEvent : null, span);
  }

  public LlmObsHandle startWorkflow(String serviceType, String methodName) {
    AiServiceEvent jfrEvent = new AiServiceEvent(serviceType, methodName);
    boolean jfrActive = jfrEvent.isEnabled();
    boolean obsActive = LLMObs.isEnabled();
    if (!jfrActive && !obsActive) return LlmObsHandle.NOOP;
    LLMObsSpan span =
        obsActive ? LLMObs.startWorkflowSpan(serviceType + "." + methodName, null, null) : null;
    if (jfrActive && span != null) {
      jfrEvent.setSpanContext(span.getTraceId().toString(), Long.toHexString(span.getSpanId()));
    }
    return new LlmCallHandle(jfrActive ? jfrEvent : null, span);
  }

  public LlmObsHandle startTool(String toolName) {
    ToolExecutorEvent jfrEvent = new ToolExecutorEvent(toolName);
    boolean jfrActive = jfrEvent.isEnabled();
    boolean obsActive = LLMObs.isEnabled();
    if (!jfrActive && !obsActive) return LlmObsHandle.NOOP;
    LLMObsSpan span = obsActive ? LLMObs.startToolSpan(toolName, null, null) : null;
    if (jfrActive && span != null) {
      jfrEvent.setSpanContext(span.getTraceId().toString(), Long.toHexString(span.getSpanId()));
    }
    return new LlmCallHandle(jfrActive ? jfrEvent : null, span);
  }
}
