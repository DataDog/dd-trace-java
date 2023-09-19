package datadog.trace.bootstrap.instrumentation.httpurlconnection;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlConnectionDecorator.DECORATE;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.HttpURLConnection;

public class HttpUrlState {
  public static final ContextStore.Factory<HttpUrlState> FACTORY = HttpUrlState::new;

  private volatile AgentSpan span = null;
  private volatile boolean finished = false;

  public AgentSpan start(final HttpURLConnection connection) {
    span = startSpan(DECORATE.operationName());
    try (final AgentScope scope = activateSpan(span)) {
      StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
      log.debug("keisuke log - current stack trace: {}", stackTraceElements);
      log.debug("keisuke log - span before DECORATE.afterStart: {}", span);
      DECORATE.afterStart(span);
      log.debug("keisuke log - span before DECORATE.onRequet: {}", span);
      DECORATE.onRequest(span, connection);
      log.debug("keisuke log - span after DECORATE.onResponse: {}", span);
      return span;
    }
  }

  public boolean hasSpan() {
    return span != null;
  }

  public boolean isFinished() {
    return finished;
  }

  public void finish() {
    finished = true;
  }

  public void finishSpan(
      final HttpURLConnection connection, final int responseCode, final Throwable throwable) {
    try (final AgentScope scope = activateSpan(span)) {
      StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
      log.debug("keisuke log - current stack trace: {}", stackTraceElements);
      if (responseCode > 0) {
        // safe to access response data as 'responseCode' is set
        log.debug("keisuke log - span before DECORATE.onResponse: {}", span);
        DECORATE.onResponse(span, connection);
        log.debug("keisuke log - span after DECORATE.onResponse: {}", span);
      } else {
        // Ignoring the throwable if we have response code
        // to have consistent behavior with other http clients.
        DECORATE.onError(span, throwable);
      }
      log.debug("keisuke log - span before DECORATE.beforeFinish: {}", span);
      DECORATE.beforeFinish(span);
      log.debug("keisuke log - span after DECORATE.beforeFinish: {}", span);
      span.finish();
      span = null;
      finished = true;
    }
  }

  public void finishSpan(final HttpURLConnection connection, final int responseCode) {
    /*
     * responseCode field is sometimes not populated.
     * We can't call getResponseCode() due to some unwanted side-effects
     * (e.g. breaks getOutputStream).
     */
    StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
    log.debug("keisuke log - current stack trace: {}", stackTraceElements);
    if (responseCode > 0) {
      try (final AgentScope scope = activateSpan(span)) {
        // safe to access response data as 'responseCode' is set
        log.debug("keisuke log - span before DECORATE.onResponse: {}", span);
        DECORATE.onResponse(span, connection);
        log.debug("keisuke log - span before DECORATE.beforeFinish: {}", span);
        DECORATE.beforeFinish(span);
        log.debug("keisuke log - span after DECORATE.beforeFinish: {}", span);
        span.finish();
        span = null;
        finished = true;
      }
    }
  }
}
