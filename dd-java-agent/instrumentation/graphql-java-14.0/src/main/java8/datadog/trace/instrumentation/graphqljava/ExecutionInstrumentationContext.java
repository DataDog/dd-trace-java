package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.ExecutionResult;
import graphql.execution.instrumentation.SimpleInstrumentationContext;

public class ExecutionInstrumentationContext extends SimpleInstrumentationContext<ExecutionResult> {
  private final AgentSpan span;

  public ExecutionInstrumentationContext(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void onCompleted(ExecutionResult result, Throwable t) {
    // TODO handle result.getErrors()
    DECORATE.onError(span, t);
    DECORATE.beforeFinish(span);
    span.finish();
  }
}
