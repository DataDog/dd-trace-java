package datadog.trace.api.llmobs.noop;

import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import java.util.Map;

public class NoOpLLMObsEvalProcessor implements LLMObs.LLMObsEvalProcessor {
  public static final NoOpLLMObsEvalProcessor INSTANCE = new NoOpLLMObsEvalProcessor();

  @Override
  public void SubmitEvaluation(
      LLMObsSpan llmObsSpan, String label, double scoreValue, Map<String, Object> tags) {}

  @Override
  public void SubmitEvaluation(
      LLMObsSpan llmObsSpan,
      String label,
      double scoreValue,
      String mlApp,
      Map<String, Object> tags) {}

  @Override
  public void SubmitEvaluation(
      LLMObsSpan llmObsSpan, String label, String categoricalValue, Map<String, Object> tags) {}

  @Override
  public void SubmitEvaluation(
      LLMObsSpan llmObsSpan,
      String label,
      String categoricalValue,
      String mlApp,
      Map<String, Object> tags) {}
}
