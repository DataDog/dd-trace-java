package datadog.trace.api.aiguard.noop;

import static datadog.trace.api.aiguard.AIGuard.Action.ALLOW;
import static java.util.Collections.emptyList;

import datadog.trace.api.aiguard.AIGuard.Evaluation;
import datadog.trace.api.aiguard.AIGuard.Message;
import datadog.trace.api.aiguard.AIGuard.Options;
import datadog.trace.api.aiguard.Evaluator;
import java.util.List;

public final class NoOpEvaluator implements Evaluator {

  @Override
  public Evaluation evaluate(final List<Message> messages, final Options options) {
    return new Evaluation(ALLOW, "AI Guard is not enabled", emptyList());
  }
}
