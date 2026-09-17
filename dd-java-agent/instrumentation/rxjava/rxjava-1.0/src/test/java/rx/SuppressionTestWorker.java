package rx;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;

import java.util.concurrent.TimeUnit;
import rx.functions.Action0;
import rx.subscriptions.Subscriptions;

/** Records propagation at the scheduling boundary without starting a background thread. */
public class SuppressionTestWorker extends Scheduler.Worker {
  public boolean propagating;

  @Override
  public Subscription schedule(Action0 action) {
    propagating = isAsyncPropagationEnabled();
    return Subscriptions.empty();
  }

  @Override
  public Subscription schedule(Action0 action, long delay, TimeUnit unit) {
    return schedule(action);
  }

  @Override
  public void unsubscribe() {}

  @Override
  public boolean isUnsubscribed() {
    return false;
  }
}
