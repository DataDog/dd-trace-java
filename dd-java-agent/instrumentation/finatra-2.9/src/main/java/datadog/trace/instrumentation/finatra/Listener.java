package datadog.trace.instrumentation.finatra;

import static datadog.trace.instrumentation.finatra.FinatraDecorator.DECORATE;

import com.twitter.finagle.http.Response;
import com.twitter.util.FutureEventListener;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;

public class Listener implements FutureEventListener<Response> {
  private final AgentScope scope;

  public Listener(final AgentScope scope) {
    this.scope = scope;
  }

  @Override
  public void onSuccess(final Response response) {
    // Don't use DECORATE.onResponse because this is the controller span
    if (Config.get().getHttpServerErrorStatuses().get(DECORATE.status(response))) {
      scope.span().setError(true);
    }

    DECORATE.beforeFinish(scope.span());
    scope.span().finish();
    scope.close();
  }

  @Override
  public void onFailure(final Throwable cause) {
    DECORATE.onError(scope.span(), cause);
    DECORATE.beforeFinish(scope.span());
    scope.span().finish();
    scope.close();
  }
}
