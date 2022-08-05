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
import graphql.language.AstPrinter;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetcher;
import graphql.validation.ValidationError;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    }
    instrumentationList.add(instrumentation);
    instrumentationList.add(new GraphQLInstrumentation());
    return new ChainedInstrumentation(instrumentationList);
  }

  public static final class State implements InstrumentationState {
    private AgentSpan span;

    public AgentSpan getSpan() {
      return span;
    }

    public void setSpan(AgentSpan span) {
      this.span = span;
    }
  }

  @Override
  public State createState() {
    return new State();
  }

  @Override
  public InstrumentationContext<ExecutionResult> beginExecution(
      InstrumentationExecutionParameters parameters) {

    final AgentSpan span = startSpan(GRAPHQL_REQUEST);
    DECORATE.afterStart(span);
    span.setMeasured(true);

    State state = parameters.getInstrumentationState();
    state.setSpan(span);

    return new ExecutionInstrumentationContext(span);
  }

  @Override
  public InstrumentationContext<ExecutionResult> beginExecuteOperation(
      InstrumentationExecuteOperationParameters parameters) {
    State state = parameters.getInstrumentationState();
    AgentSpan span = state.getSpan();

    OperationDefinition operationDefinition =
        parameters.getExecutionContext().getOperationDefinition();
    OperationDefinition.Operation operation = operationDefinition.getOperation();
    String operationType = operation.name().toLowerCase(Locale.ROOT);
    String operationName = operationDefinition.getName();

    String spanName = operationType;
    if (operationName != null && !operationName.isEmpty()) {
      spanName += " " + operationName;
    }
    span.setSpanName(spanName);

    span.setTag("graphql.operation.name", operationName);
    // TODO sanitize query?
    String query = AstPrinter.printAst(operationDefinition);
    span.setTag("graphql.query", query);

    return SimpleInstrumentationContext.noOp();
  }

  @Override
  public DataFetcher<?> instrumentDataFetcher(
      final DataFetcher<?> dataFetcher, InstrumentationFieldFetchParameters parameters) {
    State state = parameters.getInstrumentationState();
    final AgentSpan span = state.getSpan();
    return new InstrumentedDataFetcher(dataFetcher, parameters, span);
  }

  @Override
  public InstrumentationContext<Document> beginParse(
      InstrumentationExecutionParameters parameters) {
    State state = parameters.getInstrumentationState();

    final AgentSpan span = startSpan(GRAPHQL_PARSING, state.getSpan().context());
    DECORATE.afterStart(span);
    span.setMeasured(true);
    return new ParsingInstrumentationContext(span);
  }

  @Override
  public InstrumentationContext<List<ValidationError>> beginValidation(
      InstrumentationValidationParameters parameters) {
    State state = parameters.getInstrumentationState();

    final AgentSpan span = startSpan(GRAPHQL_VALIDATION, state.getSpan().context());
    DECORATE.afterStart(span);
    span.setMeasured(true);
    return new ValidationInstrumentationContext(span);
  }
}
