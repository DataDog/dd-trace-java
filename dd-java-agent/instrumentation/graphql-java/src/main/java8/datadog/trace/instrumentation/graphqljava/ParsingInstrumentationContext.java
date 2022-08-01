package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.language.Document;

public class ParsingInstrumentationContext extends SimpleInstrumentationContext<Document> {

  private final AgentSpan span;

  public ParsingInstrumentationContext(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void onCompleted(Document result, Throwable t) {
    if (t != null) {
      DECORATE.onError(span, t);
    }
    DECORATE.beforeFinish(span);
    span.finish();
  }
}
