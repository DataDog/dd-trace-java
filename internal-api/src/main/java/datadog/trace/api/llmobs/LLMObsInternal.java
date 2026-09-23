package datadog.trace.api.llmobs;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import javax.annotation.Nullable;

/** Internal bridge to LLM Observability API state. */
public final class LLMObsInternal extends LLMObs {
  private LLMObsInternal() {}

  /**
   * Null until LLM Observability starts, which is what lets the injection codecs skip the lookup
   * altogether in a service that does not use it.
   */
  @Nullable private static volatile LLMObsPropagationSource PROPAGATION_SOURCE;

  /** Sets the source of LLM Observability propagation tag values used at injection. */
  public static void setPropagationSource(LLMObsPropagationSource propagationSource) {
    PROPAGATION_SOURCE = propagationSource;
  }

  /**
   * The LLM Observability values to propagate for {@code spanContext}, or {@code null} when LLM
   * Observability is off or no local LLMObs context applies — in which case the values that arrived
   * on the inbound headers are forwarded instead.
   */
  @Nullable
  public static LLMObsPropagationValues propagationValuesFor(AgentSpanContext spanContext) {
    LLMObsPropagationSource source = PROPAGATION_SOURCE;
    return source == null ? null : source.valuesFor(spanContext);
  }

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

  /** Returns the registered user span processor, if any. */
  @Nullable
  public static LLMObsSpanProcessor getSpanProcessor() {
    return SPAN_PROCESSOR;
  }
}
