package datadog.trace.instrumentation.graphqljava;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class GraphQLDecorator extends BaseDecorator {
  public static final GraphQLDecorator DECORATE = new GraphQLDecorator();
  public static final CharSequence GRAPHQL_REQUEST =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().server().operationForProtocol("graphql"));
  public static final CharSequence GRAPHQL_PARSING = UTF8BytesString.create("graphql.parsing");
  public static final CharSequence GRAPHQL_VALIDATION =
      UTF8BytesString.create("graphql.validation");
  public static final CharSequence GRAPHQL_JAVA = UTF8BytesString.create("graphql-java");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"graphql-java"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.GRAPHQL;
  }

  @Override
  protected CharSequence component() {
    return GRAPHQL_JAVA;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setMeasured(true);
    return super.afterStart(span);
  }
}
