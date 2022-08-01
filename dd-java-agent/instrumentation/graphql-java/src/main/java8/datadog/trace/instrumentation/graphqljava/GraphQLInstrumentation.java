package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.ExecutionResult;
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
import java.util.List;
import java.util.Locale;

public final class GraphQLInstrumentation extends SimpleInstrumentation {
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

    final AgentSpan span = startSpan(GraphQLDecorator.GRAPHQL_QUERY);

    DECORATE.afterStart(span);
    // TODO onQuery (parameters.getQuery()...)
    // TODO check result.getErrors()

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
    span.setTag(
        "graphql.document",
        AstPrinter.printAst(
            operationDefinition)); // TODO graphql.document (OTel) or graphql.query (Go impl)

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

    final AgentSpan span = startSpan("graphql.parse", state.getSpan().context());
    DECORATE.afterStart(span);
    return new ParsingInstrumentationContext(span);
  }

  @Override
  public InstrumentationContext<List<ValidationError>> beginValidation(
      InstrumentationValidationParameters parameters) {
    State state = parameters.getInstrumentationState();

    final AgentSpan span = startSpan("graphql.validate", state.getSpan().context());
    DECORATE.afterStart(span);
    return new ValidationInstrumentationContext(span);
  }
}
