package org.glassfish.jersey.client;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.jaxrs.ClientTracingFilter;
import javax.ws.rs.ProcessingException;
import org.glassfish.jersey.process.internal.RequestScope;

public final class WrappingResponseCallback implements ResponseCallback {

  private final ResponseCallback delegate;
  private final ClientRequest request;

  public WrappingResponseCallback(ResponseCallback delegate, ClientRequest request) {
    this.delegate = delegate;
    this.request = request;
  }

  @Override
  public void completed(ClientResponse response, RequestScope scope) {
    delegate.completed(response, scope);
  }

  @Override
  public void failed(ProcessingException error) {
    handleProcessingException(request, error);
    delegate.failed(error);
  }

  public static void handleProcessingException(ClientRequest request, ProcessingException error) {
    final Object prop = request.getProperty(ClientTracingFilter.SPAN_PROPERTY_NAME);
    if (prop instanceof AgentSpan) {
      final AgentSpan span = (AgentSpan) prop;
      span.addThrowable(error);

      @SuppressWarnings("deprecation")
      final boolean isJaxRsExceptionAsErrorEnabled = Config.get().isJaxRsExceptionAsErrorEnabled();
      span.setError(isJaxRsExceptionAsErrorEnabled);

      span.finish();
    }
  }
}
