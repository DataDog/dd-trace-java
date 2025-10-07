package datadog.trace.api.aiguard;

import java.util.List;

public interface Evaluator {
  AIGuard.Evaluation evaluate(List<AIGuard.Message> messages, AIGuard.Options options);
}
