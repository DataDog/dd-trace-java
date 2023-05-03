package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;
import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.GRAPHQL_PARSING;
import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.GRAPHQL_REQUEST;
import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.GRAPHQL_VALIDATION;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.ExecutionResult;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentation;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
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

public final class GraphQLInstrumentation extends SimpleInstrumentation {

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

  public static final class State implements InstrumentationState {
    private AgentSpan requestSpan;
    private String query;

    public AgentSpan getRequestSpan() {
      return requestSpan;
    }

    public void setRequestSpan(AgentSpan requestSpan) {
      this.requestSpan = requestSpan;
    }

    public String getQuery() {
      return query;
    }

    public void setQuery(String query) {
      this.query = query;
    }
  }

  @Override
  public State createState() {
    return new State();
  }

  @Override
  public InstrumentationContext<ExecutionResult> beginExecution(
      InstrumentationExecutionParameters parameters) {
    final AgentSpan requestSpan = startSpan(GRAPHQL_REQUEST);
    DECORATE.afterStart(requestSpan);

    State state = parameters.getInstrumentationState();
    state.setRequestSpan(requestSpan);
    // parameters.getOperation() is null

    return new ExecutionInstrumentationContext(state);
  }

  @Override
  public InstrumentationContext<ExecutionResult> beginExecuteOperation(
      InstrumentationExecuteOperationParameters parameters) {
    State state = parameters.getInstrumentationState();
    AgentSpan requestSpan = state.getRequestSpan();

    OperationDefinition operationDefinition =
        parameters.getExecutionContext().getOperationDefinition();
    String operationName = operationDefinition.getName();

    requestSpan.setTag("graphql.operation.name", operationName);
    String resourceName = operationName != null ? operationName : state.getQuery();
    requestSpan.setResourceName(resourceName);
    return SimpleInstrumentationContext.noOp();
  }

  @Override
  public DataFetcher<?> instrumentDataFetcher(
      final DataFetcher<?> dataFetcher, InstrumentationFieldFetchParameters parameters) {
    State state = parameters.getInstrumentationState();
    final AgentSpan requestSpan = state.getRequestSpan();
    return new InstrumentedDataFetcher(dataFetcher, parameters, requestSpan);
  }

  @Override
  public InstrumentationContext<Document> beginParse(
      InstrumentationExecutionParameters parameters) {
    State state = parameters.getInstrumentationState();
    final AgentSpan parsingSpan = startSpan(GRAPHQL_PARSING, state.getRequestSpan().context());
    DECORATE.afterStart(parsingSpan);
    return new ParsingInstrumentationContext(parsingSpan, state, parameters.getQuery());
  }

  @Override
  public InstrumentationContext<List<ValidationError>> beginValidation(
      InstrumentationValidationParameters parameters) {
    State state = parameters.getInstrumentationState();

    final AgentSpan validationSpan =
        startSpan(GRAPHQL_VALIDATION, state.getRequestSpan().context());
    DECORATE.afterStart(validationSpan);
    return new ValidationInstrumentationContext(validationSpan);
  }
}
