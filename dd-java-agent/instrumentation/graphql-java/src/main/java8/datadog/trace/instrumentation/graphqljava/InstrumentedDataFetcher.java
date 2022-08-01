package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLOutputType;

public class InstrumentedDataFetcher implements DataFetcher<Object> {
  private final DataFetcher<?> dataFetcher;
  private final InstrumentationFieldFetchParameters parameters;
  private final AgentSpan span;

  public InstrumentedDataFetcher(
      DataFetcher<?> dataFetcher, InstrumentationFieldFetchParameters parameters, AgentSpan span) {
    this.dataFetcher = dataFetcher;
    this.parameters = parameters;
    this.span = span;
  }

  @Override
  public Object get(DataFetchingEnvironment environment) throws Exception {
    if (parameters.isTrivialDataFetcher()) {
      // have this method
      try (AgentScope scope = activateSpan(this.span)) {
        return dataFetcher.get(environment);
      }
    } else {
      final AgentSpan span = startSpan("graphql.field", this.span.context());
      // TODO decorate span
      GraphQLOutputType fieldType = environment.getFieldType();
      if (fieldType instanceof GraphQLNamedType) {
        String typeName = ((GraphQLNamedType) fieldType).getName();
        span.setTag("graphql.type", typeName);
      }
      try (AgentScope scope = activateSpan(span)) {
        return dataFetcher.get(environment);
      } finally {
        span.finish();
      }
    }
  }
}
