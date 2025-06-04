package datadog.trace.llmobs.domain;

import datadog.trace.api.llmobs.LLMObs;

public class LLMObsInternal extends LLMObs {

  public static void setLLMObsSpanFactory(final LLMObsSpanFactory factory) {
    LLMObs.SPAN_FACTORY = factory;
  }
}
