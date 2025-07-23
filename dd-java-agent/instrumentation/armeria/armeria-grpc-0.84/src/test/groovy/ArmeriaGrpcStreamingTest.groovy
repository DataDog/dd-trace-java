import com.linecorp.armeria.client.Clients
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.testing.junit4.server.ServerRule
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import example.GreeterGrpc
import example.Helloworld
import io.grpc.stub.StreamObserver

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

abstract class ArmeriaGrpcStreamingTest extends VersionedNamingTestBase {

  @Override
  final String service() {
    return null
  }

  @Override
  final String operation() {
    return null
  }

  final Duration timeoutDuration() {
    return Duration.ofSeconds(5)
  }

  protected abstract String clientOperation()

  protected abstract String serverOperation()

  protected boolean hasClientMessageSpans() {
    false
  }

  @Override
  boolean useStrictTraceWrites() {
    false
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.trace.grpc.ignored.inbound.methods", "example.Greeter/IgnoreInbound")
    injectSysConfig("dd.trace.grpc.ignored.outbound.methods", "example.Greeter/Ignore")
    if (hasClientMessageSpans()) {
      injectSysConfig("integration.grpc-message.enabled", "true")
    }
    // here to trigger wrapping to record scheduling time - the logic is trivial so it's enough to verify
    // that ClassCastExceptions do not arise from the wrapping
    injectSysConfig("dd.profiling.enabled", "true")
  }

  def "test conversation #name"() {
    setup:

    def msgCount = serverMessageCount
    def serverReceived = new CopyOnWriteArrayList<>()
    def clientReceived = new CopyOnWriteArrayList<>()
    def error = new AtomicReference()

    ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
          sb.service(GrpcService.builder().addService(new GreeterGrpc.GreeterImplBase() {
              @Override
              StreamObserver<Helloworld.Response> conversation(StreamObserver<Helloworld.Response> observer) {
                return new StreamObserver<Helloworld.Response>() {
                    @Override
                    void onNext(Helloworld.Response value) {

                      serverReceived << value.message

                      (1..msgCount).each {
                        if (TEST_TRACER.isAsyncPropagationEnabled()) {
                          observer.onNext(value)
                        } else {
                          observer.onError(new IllegalStateException("not async propagating!"))
                        }
                      }
                    }

                    @Override
                    void onError(Throwable t) {
                      if (TEST_TRACER.isAsyncPropagationEnabled()) {
                        error.set(t)
                        observer.onError(t)
                      } else {
                        observer.onError(new IllegalStateException("not async propagating!"))
                      }
                    }

                    @Override
                    void onCompleted() {
                      if (TEST_TRACER.isAsyncPropagationEnabled()) {
                        observer.onCompleted()
                      } else {
                        observer.onError(new IllegalStateException("not async propagating!"))
                      }
                    }
                  }
              }
            }).build())
        }
      }
    serverRule.configure(Server.builder().requestTimeout(timeoutDuration()))
    serverRule.start()

    GreeterGrpc.GreeterStub client = Clients.builder(serverRule.uri(SessionProtocol.HTTP, GrpcSerializationFormats.PROTO))
      .writeTimeout(timeoutDuration())
      .responseTimeout(timeoutDuration())
      .build(GreeterGrpc.GreeterStub)

    when:
    def streamObserver = client.conversation(new StreamObserver<Helloworld.Response>() {
        @Override
        void onNext(Helloworld.Response value) {
          if (TEST_TRACER.isAsyncPropagationEnabled()) {
            clientReceived << value.message
          } else {
            error.set(new IllegalStateException("not async propagating!"))
          }
        }

        @Override
        void onError(Throwable t) {
          if (TEST_TRACER.isAsyncPropagationEnabled()) {
            error.set(t)
          } else {
            error.set(new IllegalStateException("not async propagating!"))
          }
        }

        @Override
        void onCompleted() {
          if (!TEST_TRACER.isAsyncPropagationEnabled()) {
            error.set(new IllegalStateException("not async propagating!"))
          }
        }
      })

    clientRange.each {
      def message = Helloworld.Response.newBuilder().setMessage("call $it").build()
      streamObserver.onNext(message)
    }
    streamObserver.onCompleted()

    then:
    error.get() == null
    TEST_WRITER.waitForTraces(2)
    error.get() == null
    serverReceived == clientRange.collect { "call $it" }
    clientReceived == serverRange.collect {
      clientRange.collect {
        "call $it"
      }
    }.flatten().sort()

    assertTraces(2) {
      trace((hasClientMessageSpans() ? clientMessageCount * serverMessageCount : 0) + 1) {
        sortSpansByStart()
        span {
          operationName clientOperation()
          resourceName "example.Greeter/Conversation"
          spanType DDSpanTypes.RPC
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "armeria-grpc-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.RPC_SERVICE" "example.Greeter"
            "status.code" "OK"
            "request.type" "example.Helloworld\$Response"
            "response.type" "example.Helloworld\$Response"
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
        if (hasClientMessageSpans()) {
          (1..(clientMessageCount * serverMessageCount)).each {
            span {
              operationName "grpc.message"
              resourceName "grpc.message"
              spanType DDSpanTypes.RPC
              childOf span(0)
              errored false
              tags {
                "$Tags.COMPONENT" "armeria-grpc-client"
                "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
                "message.type" "example.Helloworld\$Response"
                defaultTagsNoPeerService()
              }
            }
          }
        }
      }
      trace(clientMessageCount + 1) {
        sortSpansByStart()
        span {
          operationName serverOperation()
          resourceName "example.Greeter/Conversation"
          spanType DDSpanTypes.RPC
          childOf trace(0).get(0)
          errored false
          tags {
            "$Tags.COMPONENT" "armeria-grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "status.code" "OK"
            defaultTags(true)
          }
        }
        clientRange.each {
          span {
            operationName "grpc.message"
            resourceName "grpc.message"
            spanType DDSpanTypes.RPC
            childOf span(0)
            errored false
            tags {
              "$Tags.COMPONENT" "armeria-grpc-server"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
              "message.type" "example.Helloworld\$Response"
              defaultTags()
            }
          }
        }
      }
    }

    cleanup:
    serverRule.stop()

    where:
    name | clientMessageCount | serverMessageCount
    "A"  | 1                  | 1
    "B"  | 2                  | 1
    "C"  | 1                  | 2
    "D"  | 2                  | 2
    "E"  | 3                  | 3
    "A"  | 1                  | 1
    "B"  | 2                  | 1
    "C"  | 1                  | 2
    "D"  | 2                  | 2
    "E"  | 3                  | 3

    clientRange = 1..clientMessageCount
    serverRange = 1..serverMessageCount
  }
}

class ArmeriaGrpcStreamingV0Test extends ArmeriaGrpcStreamingTest {

  @Override
  int version() {
    return 0
  }

  @Override
  protected String clientOperation() {
    return "grpc.client"
  }

  @Override
  protected String serverOperation() {
    return "grpc.server"
  }
}

class ArmeriaGrpcStreamingClientMessagesEnabledTest extends ArmeriaGrpcStreamingV0Test {
  @Override
  protected boolean hasClientMessageSpans() {
    true
  }
}

class ArmeriaGrpcStreamingV1ForkedTest extends ArmeriaGrpcStreamingTest {

  @Override
  int version() {
    return 1
  }

  @Override
  protected String clientOperation() {
    return "grpc.client.request"
  }

  @Override
  protected String serverOperation() {
    return "grpc.server.request"
  }
}
