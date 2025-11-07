package datadog.trace.instrumentation.servlet3;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.TIMEOUT;
import static datadog.trace.instrumentation.servlet3.Servlet3Decorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

/**
 * When a servlet is dispatched to another servlet (async, forward, or include), the original
 * servlet/server span is replaced with the dispatch span in the request attributes. This is done to
 * prevent the dispatched servlet from modifying the original span (for example with the spring web
 * instrumentation which renames based on the route). Here we want different behavior when it is a
 * dispatch span because we don't report things like the http status on the dispatch span since in
 * case of exception handling, the status reported could be wrong.
 */
public class FinishAsyncDispatchListener implements AsyncListener, Runnable {
  private final AtomicBoolean activated;
  private final AgentSpan span;
  private final boolean doOnResponse;

  public FinishAsyncDispatchListener(final AgentSpan span, boolean doOnResponse) {
    this(span, new AtomicBoolean(), doOnResponse);
  }

  public FinishAsyncDispatchListener(
      final AgentSpan span, AtomicBoolean activated, boolean doOnResponse) {
    this.span = span;
    this.activated = activated;
    this.doOnResponse = doOnResponse;
  }

  @Override
  public void onComplete(final AsyncEvent event) throws IOException {
    if (activated.compareAndSet(false, true)) {
      if (doOnResponse) {
        ServletResponse resp = event.getSuppliedResponse();
        if (resp instanceof HttpServletResponse) {
          DECORATE.onResponse(span, (HttpServletResponse) resp);
        }
      }
      ServletRequest req = event.getSuppliedRequest();
      if (null != req) {
        Object error = req.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (error instanceof Throwable) {
          DECORATE.onError(span, (Throwable) error);
        }
      }
      DECORATE.beforeFinish(span);
      maybeFinishSpan();
    }
  }

  @Override
  public void run() {
    if (activated.compareAndSet(false, true)) {
      DECORATE.beforeFinish(span);
      maybeFinishSpan();
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
      maybeFinishSpan();
    }
  }

  @Override
  public void onError(final AsyncEvent event) throws IOException {
    if (event.getThrowable() != null && activated.compareAndSet(false, true)) {
      DECORATE.onError(span, event.getThrowable());
      DECORATE.beforeFinish(span);
      maybeFinishSpan();
    }
  }

  /** Transfer the listener over to the new context. */
  @Override
  public void onStartAsync(final AsyncEvent event) throws IOException {
    event.getAsyncContext().addListener(this);
  }

  private void maybeFinishSpan() {
    if (span.phasedFinish()) {
      span.publish();
    }
  }
}
