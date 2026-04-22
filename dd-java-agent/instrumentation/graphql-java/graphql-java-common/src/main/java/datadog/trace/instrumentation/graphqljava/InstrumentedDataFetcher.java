package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLTypeUtil;
import java.util.concurrent.CompletionStage;

public class InstrumentedDataFetcher implements DataFetcher<Object> {
  private final DataFetcher<?> dataFetcher;
  private final InstrumentationFieldFetchParameters parameters;
  private final AgentSpan requestSpan;

  public InstrumentedDataFetcher(
      DataFetcher<?> dataFetcher,
      InstrumentationFieldFetchParameters parameters,
      AgentSpan requestSpan) {
    this.dataFetcher = dataFetcher;
    this.parameters = parameters;
    this.requestSpan = requestSpan;
  }

  @Override
  public Object get(DataFetchingEnvironment environment) throws Exception {
    if (parameters.isTrivialDataFetcher()) {
      try (AgentScope scope = activateSpan(this.requestSpan)) {
        return dataFetcher.get(environment);
      }
    } else {
      final AgentSpan fieldSpan = startSpan("graphql.field", this.requestSpan.context());
      DECORATE.afterStart(fieldSpan);
      String parentType = GraphQLTypeUtil.simplePrint(environment.getParentType());
      String fieldName = environment.getField().getName();
      String schemaCoordinates = parentType + '.' + fieldName;
      fieldSpan.setResourceName(schemaCoordinates);
      fieldSpan.setTag("graphql.coordinates", schemaCoordinates);
      GraphQLOutputType fieldType = environment.getFieldType();
      fieldSpan.setTag("graphql.type", GraphQLTypeUtil.simplePrint(fieldType));
      Object dataValue;
      try (AgentScope scope = activateSpan(fieldSpan)) {
        dataValue = dataFetcher.get(environment);
      } catch (Exception e) {
        DECORATE.onError(fieldSpan, e);
        DECORATE.beforeFinish(fieldSpan);
        fieldSpan.finish();
        throw e;
      }
      if (dataValue instanceof CompletionStage<?>) {
        return ((CompletionStage<?>) dataValue)
            .whenComplete(
                (result, throwable) -> {
                  DECORATE.onError(fieldSpan, AsyncExceptionUnwrapper.unwrap(throwable));
                  DECORATE.beforeFinish(fieldSpan);
                  fieldSpan.finish();
                });
      }
      DECORATE.beforeFinish(fieldSpan);
      fieldSpan.finish();
      return dataValue;
    }
  }
}
