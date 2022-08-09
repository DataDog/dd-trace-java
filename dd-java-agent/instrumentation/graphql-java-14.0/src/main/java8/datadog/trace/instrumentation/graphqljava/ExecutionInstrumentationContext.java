package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import java.util.List;
import java.util.stream.Collectors;

public class ExecutionInstrumentationContext extends SimpleInstrumentationContext<ExecutionResult> {
  private final AgentSpan requestSpan;

  public ExecutionInstrumentationContext(AgentSpan requestSpan) {
    this.requestSpan = requestSpan;
  }

  @Override
  public void onCompleted(ExecutionResult result, Throwable t) {
    List<GraphQLError> errors = result.getErrors();
    if (!errors.isEmpty()) {
      String errorMessage =
          errors.stream().map(GraphQLError::getMessage).collect(Collectors.joining("\n"));
      requestSpan.setErrorMessage(errorMessage);
      requestSpan.setError(true);
    }
    DECORATE.onError(requestSpan, t);
    DECORATE.beforeFinish(requestSpan);
    requestSpan.finish();
  }
}
