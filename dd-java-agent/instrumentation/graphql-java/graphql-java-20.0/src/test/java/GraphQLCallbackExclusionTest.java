import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static graphql.Scalars.GraphQLString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.scopediag.ScopeDiagnostics;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.instrumentation.graphqljava.InstrumentedDataFetcher;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.language.Field;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLObjectType;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.tabletest.junit.TableTest;

class GraphQLCallbackExclusionTest extends AbstractInstrumentationTest {
  @TableTest({
    "scenario                 | completed     | outcome  ",
    "successful resolver      | {false, true} | success  ",
    "plain resolver failure   | {false, true} | plain    ",
    "wrapped resolver failure | {false, true} | wrapped  ",
    "nested resolver failure  | {false, true} | nested   ",
    "execution failure        | {false, true} | execution",
    "cancelled resolver       | {false, true} | cancel   "
  })
  void fieldFinisherSkipsCapture(boolean completed, String outcome) throws Exception {
    CompletableFuture<String> source = new CompletableFuture<>();
    IllegalArgumentException cause = new IllegalArgumentException("resolver failed");
    Throwable failure =
        outcome.equals("plain")
            ? cause
            : outcome.equals("wrapped")
                ? new CompletionException(cause)
                : outcome.equals("nested")
                    ? new CompletionException(new CompletionException(cause))
                    : outcome.equals("execution") ? new ExecutionException(cause) : null;
    if (completed) {
      complete(source, outcome, failure);
    }
    GraphQLObjectType parentType =
        GraphQLObjectType.newObject()
            .name("Query")
            .field(field -> field.name("value").type(GraphQLString))
            .build();
    DataFetchingEnvironment environment = mock(DataFetchingEnvironment.class);
    when(environment.getParentType()).thenReturn(parentType);
    when(environment.getField()).thenReturn(Field.newField("value").build());
    when(environment.getFieldType()).thenReturn(GraphQLString);
    InstrumentationFieldFetchParameters parameters =
        mock(InstrumentationFieldFetchParameters.class);
    AtomicReference<AgentSpan> field = new AtomicReference<>();
    DataFetcher<CompletableFuture<String>> delegate =
        ignored -> {
          field.set(activeSpan());
          return source;
        };
    AgentSpan request = startSpan("test", "request");
    AgentSpan application = startSpan("test", "application");
    InstrumentedDataFetcher fetcher = new InstrumentedDataFetcher(delegate, parameters, request);
    ApplicationCallback callback = new ApplicationCallback();
    ContextScope scope = activateSpan(request);
    int before = captures();
    try {
      @SuppressWarnings("unchecked")
      CompletableFuture<String> dependent = (CompletableFuture<String>) fetcher.get(environment);
      int finisherCaptures = captures() - before;
      assertSame(request, activeSpan());
      scope.close();
      scope = activateSpan(application);
      before = captures();
      CompletableFuture<String> downstream = dependent.whenComplete(callback);
      int applicationCaptures = captures() - before;
      scope.close();
      scope = null;
      complete(source, outcome, failure);

      assertEquals(0, finisherCaptures);
      assertEquals(completed ? 0 : 1, applicationCaptures);
      assertNotNull(field.get());
      assertEquals(request.getSpanId(), assertInstanceOf(DDSpan.class, field.get()).getParentId());
      assertEquals("graphql.field", field.get().getOperationName().toString());
      assertEquals(1, callback.calls);
      assertSame(application, callback.parent);
      if (outcome.equals("success")) {
        assertEquals("value", downstream.join());
      } else {
        CompletionException expected = assertThrows(CompletionException.class, downstream::join);
        if (outcome.equals("cancel")) {
          assertInstanceOf(CancellationException.class, expected.getCause());
        } else {
          assertTrue(expected == failure || expected.getCause() == failure);
        }
      }
      assertEquals(!outcome.equals("success"), field.get().isError());
      if (!outcome.equals("success")) {
        assertEquals(
            outcome.equals("cancel")
                ? CancellationException.class.getName()
                : outcome.equals("nested")
                    ? CompletionException.class.getName()
                    : IllegalArgumentException.class.getName(),
            field.get().getTag("error.type"));
      }
      assertNull(activeSpan());
    } finally {
      if (scope != null) {
        scope.close();
      }
      complete(source, outcome, failure);
      request.finish();
      application.finish();
      writer.waitForTraces(2);
      assertEquals(3, writer.stream().mapToInt(List::size).sum());
    }
  }

  private static int captures() {
    return ScopeDiagnostics.report().records().size();
  }

  private static void complete(
      CompletableFuture<String> source, String outcome, Throwable failure) {
    if (outcome.equals("cancel")) {
      source.cancel(false);
    } else if (failure != null) {
      source.completeExceptionally(failure);
    } else {
      source.complete("value");
    }
  }

  private static class ApplicationCallback implements BiConsumer<String, Throwable> {
    int calls;
    AgentSpan parent;

    @Override
    public void accept(String value, Throwable failure) {
      calls++;
      parent = activeSpan();
    }
  }
}
