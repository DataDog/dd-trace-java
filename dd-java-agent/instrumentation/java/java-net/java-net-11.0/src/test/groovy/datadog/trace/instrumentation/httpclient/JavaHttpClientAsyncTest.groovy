package datadog.trace.instrumentation.httpclient

import datadog.trace.agent.test.scopediag.TrackScopeContinuations

import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static java.util.concurrent.TimeUnit.SECONDS

import java.net.http.HttpRequest
import java.net.http.HttpResponse

class JavaHttpClientAsyncTest extends JavaHttpClientTest {
  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = HttpRequest.newBuilder()
      .uri(uri)
      .method(method, HttpRequest.BodyPublishers.ofString(body))
    if (headers != null) {
      headers.each { key, value -> request.header(key, value) }
    }

    def response = client.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
    def ret = response
      .thenApply({ r -> r.statusCode() })
      .join()
    callback?.call()
    return ret
  }

  @TrackScopeContinuations
  def "application callbacks retain parents after async response: failure=#failure"() {
    setup:
    def releaseResponse = new CountDownLatch(1)
    def delayedServer = httpServer {
      handlers {
        prefix("delayed") {
          assert releaseResponse.await(10, SECONDS)
          response.status(200).send("body")
        }
      }
    }
    def uri = failure ? new URI("http://localhost:$UNUSABLE_PORT/") : delayedServer.address.resolve("/delayed")
    def handlerCalls = new AtomicInteger()
    def bodyParent = new AtomicReference()
    def callbackParent = new AtomicReference()
    def caller = new AtomicReference()
    def subscriber = new RecordingSubscriber(bodyParent)
    def handler = { info ->
      handlerCalls.incrementAndGet()
      subscriber
    } as HttpResponse.BodyHandler<String>

    when:
    def downstream = runUnderTrace("caller") {
      caller.set(activeSpan())
      def response = client.sendAsync(HttpRequest.newBuilder(uri).build(), handler)
      response.handle { result, error ->
        callbackParent.set(activeSpan())
        runUnderTrace("downstream") { }
        [result, error]
      }
    }
    releaseResponse.countDown()
    def outcome = downstream.get(10, SECONDS)

    then:
    callbackParent.get().is(caller.get())
    handlerCalls.get() == (failure ? 0 : 1)
    if (failure) {
      assert outcome[0] == null
      assert outcome[1] != null
    } else {
      assert outcome[0].body() == "body"
      assert outcome[1] == null
    }
    TEST_WRITER.waitForTraces(1)
    def spans = TEST_WRITER[0]
    spans.size() == (failure ? 3 : 4)
    def clients = spans.findAll { it.operationName.toString() == operation() }
    clients.size() == 1
    clients[0].parentId == caller.get().spanId
    clients[0].isError() == failure
    spans.find { it.operationName.toString() == "downstream" }.parentId == caller.get().spanId
    if (!failure) {
      assert bodyParent.get().spanId == clients[0].spanId
      assert spans.find { it.operationName.toString() == "body" }.parentId == clients[0].spanId
      assert subscriber.receivedUnderClient
    }

    cleanup:
    releaseResponse.countDown()
    delayedServer.close()

    where:
    failure << [false, true]
  }

  private static class RecordingSubscriber implements HttpResponse.BodySubscriber<String> {
    private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8)
    private final AtomicReference bodyParent
    boolean receivedUnderClient = true

    RecordingSubscriber(AtomicReference bodyParent) {
      this.bodyParent = bodyParent
    }

    @Override
    CompletionStage<String> getBody() {
      delegate.body
    }

    @Override
    void onSubscribe(Flow.Subscription subscription) {
      delegate.onSubscribe(subscription)
    }

    @Override
    void onNext(List<ByteBuffer> bytes) {
      receivedUnderClient &= activeSpan()?.operationName?.toString() == "http.request"
      delegate.onNext(bytes)
    }

    @Override
    void onError(Throwable error) {
      delegate.onError(error)
    }

    @Override
    void onComplete() {
      bodyParent.set(activeSpan())
      runUnderTrace("body") { }
      delegate.onComplete()
    }
  }

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "http.request"
  }
}
