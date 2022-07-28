package datadog.trace.instrumentation.graphqljava;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.graphqljava.GraphQLDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import graphql.ExecutionResult;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentation;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
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
    System.out.println(">>> beginExecution");

    final AgentSpan span = startSpan(GraphQLDecorator.GRAPHQL_QUERY);

    // TODO decorate span
    DECORATE.afterStart(span);
    // TODO onQuery (parameters.getQuery()...)
    // TODO check result.getErrors()

    State state = parameters.getInstrumentationState();
    state.setSpan(span);

    return new SimpleInstrumentationContext<ExecutionResult>() {
      @Override
      public void onCompleted(ExecutionResult result, Throwable t) {
        DECORATE.onError(span, t);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    };
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

    // TODO query
    //    Node<?> node = operationDefinition;
    //    if (sanitizeQuery) {
    //      node = sanitize(node);
    //    }
    //    state.setQuery(AstPrinter.printAst(node));

    return new SimpleInstrumentationContext<>(); // SimpleInstrumentationContext.noOp(); doesn't
    // exist in graphql-java-9.7
  }

  @Override
  public DataFetcher<?> instrumentDataFetcher(
      final DataFetcher<?> dataFetcher, InstrumentationFieldFetchParameters parameters) {
    State state = parameters.getInstrumentationState();
    final AgentSpan span = state.getSpan();

    return new DataFetcher<Object>() {
      @Override
      public Object get(DataFetchingEnvironment environment) throws Exception {
        try (AgentScope scope = activateSpan(span)) {
          return dataFetcher.get(environment);
        }
      }
    };
  }
}
