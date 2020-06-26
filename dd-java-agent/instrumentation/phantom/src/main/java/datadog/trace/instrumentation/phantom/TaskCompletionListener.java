package datadog.trace.instrumentation.phantom;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import monix.eval.Task;
import scala.Function1;
import scala.Option;
import scala.runtime.AbstractFunction1;
import scala.runtime.BoxedUnit;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
public class TaskCompletionListener extends AbstractFunction1<Option<Throwable>, Task<BoxedUnit>> {
  private final AgentSpan agentSpan;

  public TaskCompletionListener(final AgentSpan agentSpan) {
    this.agentSpan = agentSpan;
  }

  @Override
  public Task<BoxedUnit> apply(final Option<Throwable> throwableOption) {
    final AgentScope scope = activateSpan(agentSpan);
    agentSpan.finish();
    scope.setAsyncPropagation(false);
    scope.close();
    return null;
  }
}
