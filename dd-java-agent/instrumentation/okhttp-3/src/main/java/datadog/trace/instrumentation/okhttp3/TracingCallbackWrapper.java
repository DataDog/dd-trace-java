package datadog.trace.instrumentation.okhttp3;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class TracingCallbackWrapper implements Callback {
  private final Callback delegate;
  private final AgentScope.Continuation parentContinuation;

  public TracingCallbackWrapper(Callback delegate, AgentScope.Continuation parentContinuation) {
    this.delegate = delegate;
    this.parentContinuation = parentContinuation;
  }

  @Override
  public void onFailure(Call call, IOException e) {
    try (final AgentScope scope = parentContinuation.activate()) {
      delegate.onFailure(call, e);
    }
  }

  @Override
  public void onResponse(Call call, Response response) throws IOException {
    try (final AgentScope scope = parentContinuation.activate()) {
      delegate.onResponse(call, response);
    }
  }
}
