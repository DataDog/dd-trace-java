package datadog.trace.instrumentation.thrift;

import static datadog.trace.instrumentation.thrift.ThriftClientDecorator.CLIENT_DECORATOR;
import static datadog.trace.instrumentation.thrift.ThriftConstants.CLIENT_INJECT_THREAD;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataDogAsyncMethodCallback<Object> implements AsyncMethodCallback<Object> {
  public static final Logger logger = LoggerFactory.getLogger(DataDogAsyncMethodCallback.class);
  final AsyncMethodCallback<Object> callback;
  private final AgentSpan span;
  // private final AgentScope.Continuation continuation;

  public DataDogAsyncMethodCallback(AsyncMethodCallback<Object> callback, AgentSpan span) {
    this.callback = callback;
    this.span = span;
    // continuation = captureSpan(span);
  }

  @Override
  public void onComplete(final Object response) {
    try {
      if (span==null) {
        return;
      }
      CLIENT_DECORATOR.onError(span, null);
      CLIENT_DECORATOR.beforeFinish(span);
      CLIENT_INJECT_THREAD.remove();
      span.finish();
    } finally {
      callback.onComplete(response);
    }

  }

  @Override
  public void onError(final Exception exception) {
    try {
      if (span==null) {
        return;
      }
      CLIENT_DECORATOR.onError(span, exception);
      CLIENT_DECORATOR.beforeFinish(span);
      span.finish();
      CLIENT_INJECT_THREAD.remove();
    } finally {
      callback.onError(exception);
    }
  }
}
