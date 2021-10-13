import com.google.common.util.concurrent.MoreExecutors
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import example.GreeterGrpc
import example.Helloworld
import io.grpc.BindableService
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GrpcStreamingTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.trace.grpc.ignored.outbound.methods", "example.Greeter/Ignore")
  }

  def "test conversation #name"() {
    setup:

    def msgCount = serverMessageCount
    def serverReceived = new CopyOnWriteArrayList<>()
    def clientReceived = new CopyOnWriteArrayList<>()
    def error = new AtomicReference()

    BindableService greeter = new GreeterGrpc.GreeterImplBase() {
        @Override
        StreamObserver<Helloworld.Response> conversation(StreamObserver<Helloworld.Response> observer) {
          return new StreamObserver<Helloworld.Response>() {
              @Override
              void onNext(Helloworld.Response value) {

                serverReceived << value.message

                (1..msgCount).each {
                  if (TEST_TRACER.activeScope().isAsyncPropagating()) {
                    observer.onNext(value)
                  } else {
                    observer.onError(new IllegalStateException("not async propagating!"))
                  }
                }
              }

              @Override
              void onError(Throwable t) {
                if (TEST_TRACER.activeScope().isAsyncPropagating()) {
                  error.set(t)
                  observer.onError(t)
                } else {
                  observer.onError(new IllegalStateException("not async propagating!"))
                }
              }

              @Override
              void onCompleted() {
                if (TEST_TRACER.activeScope().isAsyncPropagating()) {
                  observer.onCompleted()
                } else {
                  observer.onError(new IllegalStateException("not async propagating!"))
                }
              }
            }
        }
      }
    Server server = InProcessServerBuilder.forName(getClass().name).addService(greeter)
      .executor(directExecutor ? MoreExecutors.directExecutor() : Executors.newCachedThreadPool())
      .build().start()

    ManagedChannel channel = InProcessChannelBuilder.forName(getClass().name).build()
    GreeterGrpc.GreeterStub client = GreeterGrpc.newStub(channel).withWaitForReady()

    when:
    def observer = client.conversation(new StreamObserver<Helloworld.Response>() {
        @Override
        void onNext(Helloworld.Response value) {
          if (TEST_TRACER.activeScope().isAsyncPropagating()) {
            clientReceived << value.message
          } else {
            error.set(new IllegalStateException("not async propagating!"))
          }
        }

        @Override
        void onError(Throwable t) {
          if (TEST_TRACER.activeScope().isAsyncPropagating()) {
            error.set(t)
          } else {
            error.set(new IllegalStateException("not async propagating!"))
          }
        }

        @Override
        void onCompleted() {
          if (!TEST_TRACER.activeScope().isAsyncPropagating()) {
            error.set(new IllegalStateException("not async propagating!"))
          }
        }
      })

    clientRange.each {
      def message = Helloworld.Response.newBuilder().setMessage("call $it").build()
      observer.onNext(message)
    }
    observer.onCompleted()

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
      trace((clientMessageCount * serverMessageCount) + 1) {
        span {
          operationName "grpc.client"
          resourceName "example.Greeter/Conversation"
          spanType DDSpanTypes.RPC
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "grpc-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "status.code" "OK"
            "request.type" "example.Helloworld\$Response"
            "response.type" "example.Helloworld\$Response"
            defaultTags()
          }
        }
        (1..(clientMessageCount * serverMessageCount)).each {
          span {
            operationName "grpc.message"
            resourceName "grpc.message"
            spanType DDSpanTypes.RPC
            childOf span(0)
            errored false
            tags {
              "$Tags.COMPONENT" "grpc-client"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "message.type" "example.Helloworld\$Response"
              defaultTags()
            }
          }
        }
      }
      trace(clientMessageCount + 1) {
        span {
          operationName "grpc.server"
          resourceName "example.Greeter/Conversation"
          spanType DDSpanTypes.RPC
          childOf trace(0).get(0)
          errored false
          tags {
            "$Tags.COMPONENT" "grpc-server"
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
              "$Tags.COMPONENT" "grpc-server"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
              "message.type" "example.Helloworld\$Response"
              defaultTags()
            }
          }
        }
      }
    }

    cleanup:
    channel?.shutdownNow()?.awaitTermination(10, TimeUnit.SECONDS)
    server?.shutdownNow()?.awaitTermination()

    where:
    name | clientMessageCount | serverMessageCount | directExecutor
    "A"  | 1                  | 1                  | false
    "B"  | 2                  | 1                  | false
    "C"  | 1                  | 2                  | false
    "D"  | 2                  | 2                  | false
    "E"  | 3                  | 3                  | false
    "A"  | 1                  | 1                  | true
    "B"  | 2                  | 1                  | true
    "C"  | 1                  | 2                  | true
    "D"  | 2                  | 2                  | true
    "E"  | 3                  | 3                  | true

    clientRange = 1..clientMessageCount
    serverRange = 1..serverMessageCount
  }
}
