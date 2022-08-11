package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import java.util.List;

public class ExecutionInstrumentationContext extends SimpleInstrumentationContext<ExecutionResult> {
  private final AgentSpan requestSpan;

  public ExecutionInstrumentationContext(AgentSpan requestSpan) {
    this.requestSpan = requestSpan;
  }

  @Override
  public void onCompleted(ExecutionResult result, Throwable t) {
    List<GraphQLError> errors = result.getErrors();
    if (t != null) {
      DECORATE.onError(requestSpan, t);
    } else {
      int errorCounter = errors.size();
      if (errorCounter >= 1) {
        String error = errors.get(0).getMessage();
        if (errorCounter > 1) {
          error += " (and " + (errorCounter - 1) + " more errors)";
        }
        requestSpan.setErrorMessage(error);
        requestSpan.setError(true);
      }
    }
    DECORATE.beforeFinish(requestSpan);
    requestSpan.finish();
  }
}
