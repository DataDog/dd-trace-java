package datadog.trace.instrumentation.connection_error.resteasy;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.jaxrs.ClientTracingFilter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.resteasy.client.jaxrs.internal.ClientConfiguration;

public class WrappedFuture<T> implements Future<T> {

  private final Future<T> wrapped;
  private final ClientConfiguration context;

  public WrappedFuture(final Future<T> wrapped, final ClientConfiguration context) {
    this.wrapped = wrapped;
    this.context = context;
  }

  @Override
  public boolean cancel(final boolean mayInterruptIfRunning) {
    return wrapped.cancel(mayInterruptIfRunning);
  }

  @Override
  public boolean isCancelled() {
    return wrapped.isCancelled();
  }

  @Override
  public boolean isDone() {
    return wrapped.isDone();
  }

  @Override
  public T get() throws InterruptedException, ExecutionException {
    try {
      return wrapped.get();
    } catch (final ExecutionException e) {
      handleExecutionException(e);
      throw e;
    }
  }

  @Override
  public T get(final long timeout, final TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    try {
      return wrapped.get(timeout, unit);
    } catch (final ExecutionException e) {
      handleExecutionException(e);
      throw e;
    }
  }

  private void handleExecutionException(ExecutionException e) {
    final Object prop = context.getProperty(ClientTracingFilter.SPAN_PROPERTY_NAME);
    if (prop instanceof AgentSpan) {
      final AgentSpan span = (AgentSpan) prop;
      span.addThrowable(e.getCause());

      @SuppressWarnings("deprecation")
      final boolean isJaxRsExceptionAsErrorEnabled = Config.get().isJaxRsExceptionAsErrorEnabled();
      span.setError(isJaxRsExceptionAsErrorEnabled);

      span.finish();
    }
  }
}
