package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.validation.ValidationError;
import java.util.List;

public final class ValidationInstrumentationContext
    extends SimpleInstrumentationContext<List<ValidationError>> {
  private final AgentSpan span;

  public ValidationInstrumentationContext(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void onCompleted(List<ValidationError> result, Throwable t) {
    if (t != null) {
      DECORATE.onError(span, t);
    } else if (!result.isEmpty()) {
      span.setError(true);
    }
    DECORATE.beforeFinish(span);
    span.finish();
  }
}
