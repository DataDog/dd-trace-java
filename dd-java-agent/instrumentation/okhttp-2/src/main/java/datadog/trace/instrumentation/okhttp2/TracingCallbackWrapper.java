package datadog.trace.instrumentation.okhttp2;

import com.squareup.okhttp.Callback;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.io.IOException;

public class TracingCallbackWrapper implements Callback {
  private final Callback delegate;
  private final AgentScope.Continuation parentContinuation;

  public TracingCallbackWrapper(Callback delegate, AgentScope.Continuation parentContinuation) {
    this.delegate = delegate;
    this.parentContinuation = parentContinuation;
  }

  @Override
  public void onFailure(Request request, IOException e) {
    try (final AgentScope scope = parentContinuation.activate()) {
      delegate.onFailure(request, e);
    }
  }

  @Override
  public void onResponse(Response response) throws IOException {
    try (final AgentScope scope = parentContinuation.activate()) {
      delegate.onResponse(response);
    }
  }
}
