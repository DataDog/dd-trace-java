package datadog.trace.llmobs.domain;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

public class LLMObsState {
  public static final String ROOT_SPAN_ID = "undefined";

  private static final ContextKey<LLMObsState> CONTEXT_KEY = ContextKey.named("llmobs_span");

  private AgentSpanContext parentSpanID;

  public static ContextScope attach() {
    return Context.current().with(CONTEXT_KEY, new LLMObsState()).attach();
  }

  private static LLMObsState fromContext() {
    return Context.current().get(CONTEXT_KEY);
  }

  public static AgentSpanContext getLLMObsParentContext() {
    LLMObsState state = fromContext();
    if (state != null) {
      return state.parentSpanID;
    }
    return null;
  }

  public static void setLLMObsParentContext(AgentSpanContext llmObsParentContext) {
    LLMObsState state = fromContext();
    if (state != null) {
      state.parentSpanID = llmObsParentContext;
    }
  }

}
