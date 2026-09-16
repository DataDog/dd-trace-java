package datadog.trace.api.llmobs.noop;

import datadog.trace.api.llmobs.LLMObs;

public class NoOpLLMObsFeedbackProcessor implements LLMObs.LLMObsFeedbackProcessor {
  public static final NoOpLLMObsFeedbackProcessor INSTANCE = new NoOpLLMObsFeedbackProcessor();

  @Override
  public void submitFeedback(LLMObs.Feedback feedback) {}
}
