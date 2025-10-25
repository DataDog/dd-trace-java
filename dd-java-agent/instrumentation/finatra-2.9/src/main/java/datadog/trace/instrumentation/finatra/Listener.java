package datadog.trace.instrumentation.finatra;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.finatra.FinatraDecorator.DECORATE;

import com.twitter.finagle.http.Response;
import com.twitter.util.FutureEventListener;
import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class Listener implements FutureEventListener<Response> {
  private final ContextScope scope;

  public Listener(final ContextScope scope) {
    this.scope = scope;
  }

  @Override
  public void onSuccess(final Response response) {
    final AgentSpan span = spanFromContext(scope.context());
    // Don't use DECORATE.onResponse because this is the controller span
    if (Config.get().getHttpServerErrorStatuses().get(DECORATE.status(response))) {
      span.setError(true);
    }

    DECORATE.beforeFinish(scope.context());
    span.finish();
    scope.close();
  }

  @Override
  public void onFailure(final Throwable cause) {
    final AgentSpan span = spanFromContext(scope.context());
    DECORATE.onError(span, cause);
    DECORATE.beforeFinish(scope.context());
    span.finish();
    scope.close();
  }
}
