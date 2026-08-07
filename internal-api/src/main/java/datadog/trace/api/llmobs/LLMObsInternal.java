package datadog.trace.api.llmobs;

import javax.annotation.Nullable;

/** Internal bridge to LLM Observability API state. */
public final class LLMObsInternal extends LLMObs {
  private LLMObsInternal() {}

  /** Sets the LLM Observability span factory. */
  public static void setSpanFactory(LLMObsSpanFactory factory) {
    SPAN_FACTORY = factory;
  }

  /** Sets the LLM Observability evaluation processor. */
  public static void setEvalProcessor(LLMObsEvalProcessor evalProcessor) {
    EVAL_PROCESSOR = evalProcessor;
  }

  /** Returns the registered user span processor, if any. */
  @Nullable
  public static LLMObsSpanProcessor getSpanProcessor() {
    return SPAN_PROCESSOR;
  }
}
