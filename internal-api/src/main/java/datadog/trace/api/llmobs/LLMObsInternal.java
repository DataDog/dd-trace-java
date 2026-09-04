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

  /** Sets the LLM Observability feedback processor. */
  public static void setFeedbackProcessor(LLMObsFeedbackProcessor feedbackProcessor) {
    FEEDBACK_PROCESSOR = feedbackProcessor;
  }

  /** Sets the LLM Observability distributed tracing propagator. */
  public static void setPropagator(LLMObsPropagator propagator) {
    PROPAGATOR = propagator;
  }

  /** Returns the registered user span processor, if any. */
  @Nullable
  public static LLMObsSpanProcessor getSpanProcessor() {
    return SPAN_PROCESSOR;
  }
}
