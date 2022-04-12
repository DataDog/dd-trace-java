package datadog.trace.bootstrap.instrumentation.httpurlconnection;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlConnectionDecorator.DECORATE;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;

public class HttpUrlState {

  public static final String OPERATION_NAME = "http.request";
  public static final String HTTP_TRACE_ENABLED_KEY = "x-datadog-tracing-enabled";

  public static final ContextStore.Factory<HttpUrlState> FACTORY =
      new ContextStore.Factory<HttpUrlState>() {
        @Override
        public HttpUrlState create() {
          return new HttpUrlState();
        }
      };

  private volatile AgentSpan span = null;
  private volatile boolean finished = false;

  private boolean shouldTrace(final HttpURLConnection connection) {
    try {
      Map<String,List<String>> map = connection.getRequestProperties();
      return (
        null == map
        || null == map.get(HTTP_TRACE_ENABLED_KEY)
        || null == map.get(HTTP_TRACE_ENABLED_KEY).get(0)
        || "false" != map.get(HTTP_TRACE_ENABLED_KEY).get(0)
      );
    } catch (IllegalStateException e) {
      return false;
    }
  }

  public AgentSpan start(final HttpURLConnection connection) {
    if (shouldTrace(connection)) {
      System.out.println("should trace : " + connection.getURL());
      span = startSpan(OPERATION_NAME);
      try (final AgentScope scope = activateSpan(span)) {
        DECORATE.afterStart(span);
        DECORATE.onRequest(span, connection);
        return span;
      }
    }
    System.out.println("should not trace : " + connection.getURL());
    return null;
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

  public void finishSpan(final int responseCode, final Throwable throwable) {
    try (final AgentScope scope = activateSpan(span)) {
      if (responseCode > 0) {
        DECORATE.onResponse(span, responseCode);
      } else {
        // Ignoring the throwable if we have response code
        // to have consistent behavior with other http clients.
        DECORATE.onError(span, throwable);
      }
      DECORATE.beforeFinish(span);
      span.finish();
      span = null;
      finished = true;
    }
  }

  public void finishSpan(final int responseCode) {
    /*
     * responseCode field is sometimes not populated.
     * We can't call getResponseCode() due to some unwanted side-effects
     * (e.g. breaks getOutputStream).
     */
    if (responseCode > 0) {
      try (final AgentScope scope = activateSpan(span)) {
        DECORATE.onResponse(span, responseCode);
        DECORATE.beforeFinish(span);
        span.finish();
        span = null;
        finished = true;
      }
    }
  }
}
