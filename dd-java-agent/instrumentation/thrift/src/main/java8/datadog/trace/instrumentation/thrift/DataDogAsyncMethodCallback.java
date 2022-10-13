package datadog.trace.instrumentation.thrift;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static datadog.trace.instrumentation.thrift.ThriftClientDecorator.CLIENT_DECORATOR;
import static datadog.trace.instrumentation.thrift.ThriftConstants.CLIENT_INJECT_THREAD;

public class DataDogAsyncMethodCallback<Object> implements AsyncMethodCallback<Object> {
  public static final Logger logger = LoggerFactory.getLogger(DataDogAsyncMethodCallback.class);
  final AsyncMethodCallback<Object> callback;
  AgentScope scope;

  public DataDogAsyncMethodCallback(AsyncMethodCallback<Object> callback, AgentScope scope) {
    this.callback = callback;
    this.scope = scope;
    logger.debug("init DataDogAsyncMethodCallback");
  }

  @Override
  public void onComplete(final Object response) {
    logger.debug("do onComplete");
    if (!Optional.ofNullable(scope).isPresent()) {
      return;
    }
    try {
      logger.debug("onComplete scope is not null,thread:" + Thread.currentThread().getName());
      CLIENT_DECORATOR.onError(scope.span(), null);
      CLIENT_DECORATOR.beforeFinish(scope.span());
      CLIENT_INJECT_THREAD.remove();
      scope.close();
      scope.span().finish();
    } finally {
      callback.onComplete(response);
    }
  }

  @Override
  public void onError(final Exception exception) {
    if (!Optional.ofNullable(scope).isPresent()) {
      return;
    }
    try {
      CLIENT_DECORATOR.onError(scope.span(), exception);
      CLIENT_DECORATOR.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
      CLIENT_INJECT_THREAD.remove();
    } finally {
      callback.onError(exception);
    }
  }
}
