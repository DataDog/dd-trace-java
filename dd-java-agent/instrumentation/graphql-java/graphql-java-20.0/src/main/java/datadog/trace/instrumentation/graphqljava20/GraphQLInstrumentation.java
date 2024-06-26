package datadog.trace.instrumentation.graphqljava20;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.graphqljava.ExecutionInstrumentationContext;
import datadog.trace.instrumentation.graphqljava.GraphQLDecorator;
import datadog.trace.instrumentation.graphqljava.InstrumentedDataFetcher;
import datadog.trace.instrumentation.graphqljava.ParsingInstrumentationContext;
import datadog.trace.instrumentation.graphqljava.State;
import datadog.trace.instrumentation.graphqljava.ValidationInstrumentationContext;
import graphql.ExecutionResult;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.execution.instrumentation.parameters.InstrumentationValidationParameters;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetcher;
import graphql.validation.ValidationError;
import java.util.ArrayList;
import java.util.List;

public final class GraphQLInstrumentation extends SimplePerformantInstrumentation {

  public static Instrumentation install(Instrumentation instrumentation) {
    if (instrumentation == null) {
      return new GraphQLInstrumentation();
    }
    if (instrumentation.getClass() == GraphQLInstrumentation.class) {
      return instrumentation;
    }
    List<Instrumentation> instrumentationList = new ArrayList<>();
    if (instrumentation instanceof ChainedInstrumentation) {
      List<Instrumentation> instrumentations =
          ((ChainedInstrumentation) instrumentation).getInstrumentations();
      if (instrumentations.stream().anyMatch(v -> v.getClass() == GraphQLInstrumentation.class)) {
        return instrumentation;
      }
      instrumentationList.addAll(instrumentations);
    } else {
      instrumentationList.add(instrumentation);
    }
    instrumentationList.add(new GraphQLInstrumentation());
    return new ChainedInstrumentation(instrumentationList);
  }

  @Override
  public State createState(InstrumentationCreateStateParameters parameters) {
    return new State();
  }

  @Override
  public InstrumentationContext<ExecutionResult> beginExecution(
      InstrumentationExecutionParameters parameters, InstrumentationState instrumentationState) {
    if (!(instrumentationState instanceof State)) {
      return super.beginExecution(parameters, instrumentationState);
    }
    final State state = (State) instrumentationState;
    final AgentSpan requestSpan = startSpan(GraphQLDecorator.GRAPHQL_REQUEST);
    GraphQLDecorator.DECORATE.afterStart(requestSpan);

    state.setRequestSpan(requestSpan);
    // parameters.getOperation() is null

    return new ExecutionInstrumentationContext(state);
  }

  @Override
  public InstrumentationContext<ExecutionResult> beginExecuteOperation(
      InstrumentationExecuteOperationParameters parameters,
      InstrumentationState instrumentationState) {
    if (!(instrumentationState instanceof State)) {
      return super.beginExecuteOperation(parameters, instrumentationState);
    }
    final State state = (State) instrumentationState;
    AgentSpan requestSpan = state.getRequestSpan();

    OperationDefinition operationDefinition =
        parameters.getExecutionContext().getOperationDefinition();
    String operationName = operationDefinition.getName();

    requestSpan.setTag("graphql.operation.name", operationName);
    String resourceName = operationName != null ? operationName : state.getQuery();
    requestSpan.setResourceName(resourceName);
    GraphQLDecorator.DECORATE.onRequest(requestSpan, parameters.getExecutionContext());
    return SimpleInstrumentationContext.noOp();
  }

  @Override
  public DataFetcher<?> instrumentDataFetcher(
      final DataFetcher<?> dataFetcher,
      InstrumentationFieldFetchParameters parameters,
      InstrumentationState instrumentationState) {
    if (!(instrumentationState instanceof State)) {
      return super.instrumentDataFetcher(dataFetcher, parameters, instrumentationState);
    }
    final State state = (State) instrumentationState;
    final AgentSpan requestSpan = state.getRequestSpan();
    return new InstrumentedDataFetcher(dataFetcher, parameters, requestSpan);
  }

  @Override
  public InstrumentationContext<Document> beginParse(
      InstrumentationExecutionParameters parameters, InstrumentationState instrumentationState) {
    if (!(instrumentationState instanceof State)) {
      return super.beginParse(parameters, instrumentationState);
    }
    final State state = (State) instrumentationState;
    final AgentSpan parsingSpan =
        AgentTracer.startSpan(GraphQLDecorator.GRAPHQL_PARSING, state.getRequestSpan().context());
    GraphQLDecorator.DECORATE.afterStart(parsingSpan);
    return new ParsingInstrumentationContext(parsingSpan, state, parameters.getQuery());
  }

  @Override
  public InstrumentationContext<List<ValidationError>> beginValidation(
      InstrumentationValidationParameters parameters, InstrumentationState instrumentationState) {
    if (!(instrumentationState instanceof State)) {
      return super.beginValidation(parameters, instrumentationState);
    }
    final State state = (State) instrumentationState;

    final AgentSpan validationSpan =
        AgentTracer.startSpan(
            GraphQLDecorator.GRAPHQL_VALIDATION, state.getRequestSpan().context());
    GraphQLDecorator.DECORATE.afterStart(validationSpan);
    return new ValidationInstrumentationContext(validationSpan);
  }
}
