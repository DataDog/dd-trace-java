import com.datadog.debugger.agent.ClassesToRetransformFinder
import com.datadog.debugger.agent.Configuration
import com.datadog.debugger.agent.ConfigurationUpdater
import com.datadog.debugger.agent.DebuggerTransformer
import com.datadog.debugger.agent.DenyListHelper
import com.datadog.debugger.agent.JsonSnapshotSerializer
import com.datadog.debugger.codeorigin.DefaultCodeOriginRecorder
import com.datadog.debugger.instrumentation.InstrumentationResult
import com.datadog.debugger.probe.ProbeDefinition
import com.datadog.debugger.sink.DebuggerSink
import com.datadog.debugger.sink.ProbeStatusSink
import com.google.common.util.concurrent.MoreExecutors
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.debugger.DebuggerContext
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.util.AgentTaskScheduler
import example.GreeterGrpc
import example.Helloworld
import io.grpc.BindableService
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import net.bytebuddy.agent.ByteBuddyAgent

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.api.config.TraceInstrumentationConfig.*
import static datadog.trace.util.AgentThreadFactory.AgentThread.TASK_SCHEDULER
import static java.lang.String.format
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

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

    Thread.sleep(1000)
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

    assertTraces(2) {
      trace((hasClientMessageSpans() ? clientMessageCount * serverMessageCount : 0) + 1) {
        span {
          operationName clientOperation()
          resourceName "example.Greeter/Conversation"
          spanType DDSpanTypes.RPC
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "grpc-client"
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
                "$Tags.COMPONENT" "grpc-client"
                "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
                "message.type" "example.Helloworld\$Response"
                defaultTagsNoPeerService()
              }
            }
          }
        }
      }
      trace(clientMessageCount + 1) {
        span {
          operationName serverOperation()
          resourceName "example.Greeter/Conversation"
          spanType DDSpanTypes.RPC
          childOf trace(0).get(0)
          errored false
          tags {
            "$Tags.COMPONENT" "grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "status.code" "OK"
            isPresent(DDTags.DD_CODE_ORIGIN_TYPE)

            for (label in ["file", "line", "method", "type", "signature"]) {
              isPresent(format(DDTags.DD_CODE_ORIGIN_FRAME, 0, label))
            }
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


  void codeOriginSetup() {
    injectSysConfig(CODE_ORIGIN_FOR_SPANS_ENABLED, "true", true)

    def configuration = Configuration.builder()
    .setService("grpc code origin test")
    .build()

    def config = mock(Config.class)
    when(config.isDebuggerEnabled()).thenReturn(true)
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true)
    when(config.isDebuggerVerifyByteCode()).thenReturn(false)
    when(config.getFinalDebuggerSnapshotUrl())
    .thenReturn("http://localhost:8126/debugger/v1/input")
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input")
    when(config.getDebuggerCodeOriginMaxUserFrames()).thenReturn(8)

    def probeStatusSink = mock(ProbeStatusSink.class)

    def sink = new DebuggerSink(config, probeStatusSink)
    def configurationUpdater = new ConfigurationUpdater(INSTRUMENTATION, DebuggerTransformer::new, config, sink, new ClassesToRetransformFinder())

    def currentTransformer = new DebuggerTransformer(config, configuration, {
      ProbeDefinition definition, InstrumentationResult result ->
    }, sink)
    INSTRUMENTATION.addTransformer(currentTransformer)

    DebuggerContext.initProbeResolver(configurationUpdater)
    DebuggerContext.initClassFilter(new DenyListHelper(null))
    DebuggerContext.initValueSerializer(new JsonSnapshotSerializer())

    DebuggerContext.initCodeOrigin(new DefaultCodeOriginRecorder(config, configurationUpdater))
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
