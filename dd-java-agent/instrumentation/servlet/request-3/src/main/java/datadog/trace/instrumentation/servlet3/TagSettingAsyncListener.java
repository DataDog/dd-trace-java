package datadog.trace.instrumentation.servlet3;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.TIMEOUT;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator._500;
import static datadog.trace.instrumentation.servlet3.Servlet3Decorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServletResponse;

public class TagSettingAsyncListener implements AsyncListener {
  private final AtomicBoolean activated;
  private final AgentSpan span;

  /**
   * When a servlet is dispatched to another servlet (async, forward, or include), the original
   * servlet/server span is replaced with the dispatch span in the request attributes. This is done
   * to prevent the dispatched servlet from modifying the original span (for example with the spring
   * web instrumentation which renames based on the route). Here we want different behavior when it
   * is a dispatch span because we don't report things like the http status on the dispatch span
   * since in case of exception handling, the status reported could be wrong.
   */
  private final boolean isDispatch;

  public TagSettingAsyncListener(
      final AtomicBoolean activated, final AgentSpan span, boolean isDispatch) {
    this.activated = activated;
    this.span = span;
    this.isDispatch = isDispatch;
  }

  @Override
  public void onComplete(final AsyncEvent event) throws IOException {
    if (activated.compareAndSet(false, true)) {
      if (!isDispatch) {
        DECORATE.onResponse(span, (HttpServletResponse) event.getSuppliedResponse());
      }
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  @Override
  public void onTimeout(final AsyncEvent event) throws IOException {
    if (activated.compareAndSet(false, true)) {
      if (Config.get().isServletAsyncTimeoutError()) {
        span.setError(true);
      }
      span.setTag(TIMEOUT, event.getAsyncContext().getTimeout());
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  @Override
  public void onError(final AsyncEvent event) throws IOException {
    if (event.getThrowable() != null && activated.compareAndSet(false, true)) {
      if (!isDispatch) {
        DECORATE.onResponse(span, (HttpServletResponse) event.getSuppliedResponse());
        if (((HttpServletResponse) event.getSuppliedResponse()).getStatus()
            == HttpServletResponse.SC_OK) {
          // exception is thrown in filter chain, but status code is incorrect
          span.setTag(Tags.HTTP_STATUS, _500);
        }
      }
      DECORATE.onError(span, event.getThrowable());
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  /** Transfer the listener over to the new context. */
  @Override
  public void onStartAsync(final AsyncEvent event) throws IOException {
    event.getAsyncContext().addListener(this);
  }
}
