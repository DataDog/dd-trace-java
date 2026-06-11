package datadog.trace.instrumentation.langchain4j;

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
    AgentScope agentScope = startApmSpan(OPERATION_CHAT_MODEL, modelId);
    if (!jfrActive && !obsActive && agentScope == null) return LlmObsHandle.NOOP;
    LLMObsSpan obsSpan = obsActive ? LLMObs.startLLMSpan(modelId, modelId, null, null, null) : null;
    if (jfrActive && obsSpan != null) {
      jfrEvent.setSpanContext(obsSpan.getTraceId().toString(), Long.toHexString(obsSpan.getSpanId()));
    }
    return new LlmCallHandle(jfrActive ? jfrEvent : null, obsSpan, agentScope);
  }

  public LlmObsHandle startWorkflow(String serviceType, String methodName) {
    AiServiceEvent jfrEvent = new AiServiceEvent(serviceType, methodName);
    boolean jfrActive = jfrEvent.isEnabled();
    boolean obsActive = LLMObs.isEnabled();
    AgentScope agentScope = startApmSpan(OPERATION_AI_SERVICE, serviceType + "." + methodName);
    if (!jfrActive && !obsActive && agentScope == null) return LlmObsHandle.NOOP;
    LLMObsSpan obsSpan =
        obsActive ? LLMObs.startWorkflowSpan(serviceType + "." + methodName, null, null) : null;
    if (jfrActive && obsSpan != null) {
      jfrEvent.setSpanContext(obsSpan.getTraceId().toString(), Long.toHexString(obsSpan.getSpanId()));
    }
    return new LlmCallHandle(jfrActive ? jfrEvent : null, obsSpan, agentScope);
  }

  public LlmObsHandle startTool(String toolName) {
    ToolExecutorEvent jfrEvent = new ToolExecutorEvent(toolName);
    boolean jfrActive = jfrEvent.isEnabled();
    boolean obsActive = LLMObs.isEnabled();
    AgentScope agentScope = startApmSpan(OPERATION_TOOL_EXECUTOR, toolName);
    if (!jfrActive && !obsActive && agentScope == null) return LlmObsHandle.NOOP;
    LLMObsSpan obsSpan = obsActive ? LLMObs.startToolSpan(toolName, null, null) : null;
    if (jfrActive && obsSpan != null) {
      jfrEvent.setSpanContext(obsSpan.getTraceId().toString(), Long.toHexString(obsSpan.getSpanId()));
    }
    return new LlmCallHandle(jfrActive ? jfrEvent : null, obsSpan, agentScope);
  }

  private static AgentScope startApmSpan(CharSequence operationName, String resourceName) {
    if (!AgentTracer.isRegistered()) return null;
    AgentSpan span = AgentTracer.startSpan(INSTRUMENTATION_NAME, operationName);
    span.setTag(Tags.COMPONENT, INSTRUMENTATION_NAME);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    if (resourceName != null) {
      span.setResourceName(resourceName);
    }
    return AgentTracer.activateSpan(span);
  }
}
