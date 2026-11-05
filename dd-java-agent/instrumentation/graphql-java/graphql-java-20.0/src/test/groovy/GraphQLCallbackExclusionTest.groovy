import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.scopediag.ScopeDiagnostics
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.instrumentation.graphqljava.InstrumentedDataFetcher
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.function.BiConsumer

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static graphql.Scalars.GraphQLString

class GraphQLCallbackExclusionTest extends InstrumentationSpecification {
  def "field finisher skips capture: completed=#completed outcome=#outcome"() {
    setup:
    def source = new CompletableFuture<String>()
    def cause = new IllegalArgumentException("resolver failed")
    Throwable failure = outcome == "plain" ? cause : outcome == "wrapped" ? new CompletionException(cause) :
      outcome == "nested" ? new CompletionException(new CompletionException(cause)) : outcome == "execution" ? new ExecutionException(cause) : null
    if (completed) {
      complete(source, outcome, failure)
    }
    def parentType = GraphQLObjectType.newObject().name("Query")
      .field { it.name("value").type(GraphQLString) }.build()
    def environment = Stub(DataFetchingEnvironment) {
      getParentType() >> parentType
      getField() >> Field.newField("value").build()
      getFieldType() >> GraphQLString
    }
    def parameters = Stub(InstrumentationFieldFetchParameters) {
      isTrivialDataFetcher() >> false
    }
    AgentSpan field = null
    def delegate = Stub(DataFetcher) {
      get(_) >> {
        field = activeSpan()
        source
      }
    }
    def request = startSpan("test", "request")
    def application = startSpan("test", "application")
    def fetcher = new InstrumentedDataFetcher(delegate, parameters, request)
    def callback = new ApplicationCallback()
    def scope = activateSpan(request)
    int before = captures()

    when:
    def dependent = fetcher.get(environment)
    int finisherCaptures = captures() - before
    assert activeSpan().is(request)
    scope.close()
    scope = activateSpan(application)
    before = captures()
    def downstream = dependent.whenComplete(callback)
    int applicationCaptures = captures() - before
    scope.close()
    scope = null
    complete(source, outcome, failure)

    then:
    finisherCaptures == 0
    applicationCaptures == (completed ? 0 : 1)
    field != null
    field.parentId == request.spanId
    field.operationName.toString() == "graphql.field"
    callback.calls == 1
    callback.parent.is(application)
    if (outcome == "success") {
      assert downstream.join() == "value"
    } else {
      try {
        downstream.join()
        assert false: "expected exceptional completion"
      } catch (CompletionException expected) {
        assert outcome == "cancel" ? expected.cause instanceof java.util.concurrent.CancellationException :
        expected.is(failure) || expected.cause.is(failure)
      }
    }
    field.isError() == (outcome != "success")
    if (outcome != "success") {
      assert field.getTag("error.type") == (outcome == "cancel" ? java.util.concurrent.CancellationException.name :
      outcome == "nested" ? CompletionException.name : IllegalArgumentException.name)
    }
    activeSpan() == null

    cleanup:
    scope?.close()
    complete(source, outcome, failure)
    request.finish()
    application.finish()
    TEST_WRITER.waitForTraces(2)
    assert TEST_WRITER.collectMany { it }.size() == 3

    where:
    [completed, outcome] << [[false, true], ["success", "plain", "wrapped", "nested", "execution", "cancel"]].combinations()
  }

  private static int captures() {
    ScopeDiagnostics.report().records().size()
  }

  private static void complete(CompletableFuture source, String outcome, Throwable failure) {
    if (outcome == "cancel") {
      source.cancel(false)
    } else if (failure != null) {
      source.completeExceptionally(failure)
    } else {
      source.complete("value")
    }
  }

  private static class ApplicationCallback implements BiConsumer<String, Throwable> {
    int calls
    AgentSpan parent

    @Override
    void accept(String value, Throwable failure) {
      calls++
      parent = activeSpan()
    }
  }
}
