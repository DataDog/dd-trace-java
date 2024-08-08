package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.language.Document;

public class ParsingInstrumentationContext extends SimpleInstrumentationContext<Document> {

  private final AgentSpan parsingSpan;
  private final State state;
  private final String rawQuery;

  public ParsingInstrumentationContext(AgentSpan parsingSpan, State state, String rawQuery) {
    this.parsingSpan = parsingSpan;
    this.state = state;
    this.rawQuery = rawQuery;
  }

  @Override
  public void onCompleted(Document result, Throwable t) {
    if (t != null) {
      DECORATE.onError(parsingSpan, t);
      // fail to parse query use the original raw query
      state.setQuery(rawQuery);
    } else {
      // parse successfully use sanitized query
      state.setQuery(GraphQLQuerySanitizer.sanitizeQuery(result));
    }
    DECORATE.beforeFinish(parsingSpan);
    parsingSpan.finish();
  }
}
