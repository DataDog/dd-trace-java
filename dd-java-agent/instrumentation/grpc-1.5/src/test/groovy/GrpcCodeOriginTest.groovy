import com.google.common.util.concurrent.MoreExecutors
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.bootstrap.debugger.DebuggerContext
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

import static datadog.trace.agent.test.asserts.TagsAssert.assertTags


abstract class GrpcCodeOriginTest extends VersionedNamingTestBase {
  @Override
  final String service() {
    return null
  }

  @Override
  final String operation() {
    return null
  }

  protected abstract String clientOperation()

  protected abstract String serverOperation()

  protected boolean hasClientMessageSpans() {
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
    codeOriginSetup()
  }

  def "code origin test #name"() {
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
    def streamObserver = client.conversation(new StreamObserver<Helloworld.Response>() {
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

    assert DebuggerContext.codeOriginRecorder != null
    def span = TEST_WRITER.flatten().find {
      it.operationName.toString() == "grpc.server.request"
    }
    assertTags(span, {
      it.codeOriginTags()
    }, false)

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

class GrpcCodeOriginForkedTest extends GrpcCodeOriginTest {

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
