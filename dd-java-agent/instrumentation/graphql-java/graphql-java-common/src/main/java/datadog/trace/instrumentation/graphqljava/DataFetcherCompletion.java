package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.BiConsumer;

public final class DataFetcherCompletion implements BiConsumer<Object, Throwable> {
  private final AgentSpan span;

  public DataFetcherCompletion(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void accept(Object result, Throwable throwable) {
    DECORATE.onError(span, AsyncExceptionUnwrapper.unwrap(throwable));
    DECORATE.beforeFinish(span);
    span.finish();
  }
}
