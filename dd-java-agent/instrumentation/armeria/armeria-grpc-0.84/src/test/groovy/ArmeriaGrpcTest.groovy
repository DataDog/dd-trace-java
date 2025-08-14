import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_SERVER_ERROR_STATUSES

import com.google.common.util.concurrent.ListenableFuture
import com.linecorp.armeria.client.Clients
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.testing.junit4.server.ServerRule
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.datastreams.StatsGroup
import datadog.trace.instrumentation.armeria.grpc.server.GrpcExtractAdapter
import example.GreeterGrpc
import example.Helloworld
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import spock.lang.Shared

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.gateway.Events.EVENTS

abstract class ArmeriaGrpcTest extends VersionedNamingTestBase {

  @Shared
  def ig

  def collectedAppSecHeaders = [:]
  boolean appSecHeaderDone = false
  def collectedAppSecServerMethods = []
  def collectedAppSecReqMsgs = []

  final Duration timeoutDuration() {
    return Duration.ofSeconds(5)
  }

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
      injectSysConfig("integration.armeria-grpc-message.enabled", "true")
    }
    // here to trigger wrapping to record scheduling time - the logic is trivial so it's enough to verify
    // that ClassCastExceptions do not arise from the wrapping
    injectSysConfig("dd.profiling.enabled", "true")
    injectSysConfig(GRPC_SERVER_ERROR_STATUSES, "2-14", true)
  }

  @Override
  boolean useStrictTraceWrites() {
    false
  }

  def setupSpec() {
    ig = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC)
  }

  def setup() {
    ig.registerCallback(EVENTS.requestStarted(), { -> new Flow.ResultFlow(new Object()) } as Supplier<Flow>)
    ig.registerCallback(EVENTS.requestHeader(), { reqCtx, name, value ->
      collectedAppSecHeaders[name] = value
    } as TriConsumer<RequestContext, String, String>)
    ig.registerCallback(EVENTS.requestHeaderDone(), {
      appSecHeaderDone = true
      Flow.ResultFlow.empty()
    } as Function<RequestContext, Flow<Void>>)
    ig.registerCallback(EVENTS.grpcServerRequestMessage(), { reqCtx, obj ->
      collectedAppSecReqMsgs << obj
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, Object, Flow<Void>>)
    ig.registerCallback(EVENTS.grpcServerMethod(), { reqCtx, method ->
      collectedAppSecServerMethods << method
      Flow.ResultFlow.empty()
    } as BiFunction<RequestContext, String, Flow<Void>>)
  }

  def cleanup() {
    ig.reset()
  }

  def "test request-response"() {
    setup:
    ExecutorService responseExecutor = Executors.newSingleThreadExecutor()
    ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
          sb.service(GrpcService.builder().addService(new GreeterGrpc.GreeterImplBase() {
              @Override
              void sayHello(
                final Helloworld.Request req, final StreamObserver<Helloworld.Response> responseObserver) {
                final Helloworld.Response reply = Helloworld.Response.newBuilder().setMessage("Hello $req.name").build()
                responseExecutor.execute {
                  if (TEST_TRACER.activeSpan() == null) {
                    responseObserver.onError(new IllegalStateException("no active span"))
                  } else {
                    responseObserver.onNext(reply)
                    responseObserver.onCompleted()
                  }
                }
              }
            }).build())
        }
      }
    serverRule.configure(Server.builder().requestTimeout(timeoutDuration()))
    serverRule.start()

    GreeterGrpc.GreeterFutureStub client = Clients.builder(serverRule.uri(SessionProtocol.HTTP, GrpcSerializationFormats.PROTO))
      .writeTimeout(timeoutDuration())
      .responseTimeout(timeoutDuration())
      .build(GreeterGrpc.GreeterFutureStub)


    def response = null

    when:
    runUnderTrace("parent") {
      ListenableFuture<Helloworld.Response> responseListenableFuture = client.sayHello(Helloworld.Request.newBuilder().setName(name).build())
      response = responseListenableFuture.get()
    }
    // wait here to make checkpoint asserts deterministic
    TEST_WRITER.waitForTraces(2)
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(2)
    }

    then:
    response.message == "Hello $name"
    assertTraces(2) {
      sortSpansByStart()
      trace(hasClientMessageSpans() ? 3 : 2) {
        basicSpan(it, "parent")
        span {
          operationName clientOperation()
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          childOf span(0)
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.RPC_SERVICE" "example.Greeter"
            "grpc.status.code" "OK"
            "request.type" "example.Helloworld\$Request"
            "response.type" "example.Helloworld\$Response"
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
        if (hasClientMessageSpans()) {
          span {
            operationName "grpc.message"
            resourceName "grpc.message"
            spanType DDSpanTypes.RPC
            childOf span(1)
            errored false
            measured true
            tags {
              "$Tags.COMPONENT" "armeria-grpc-client"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "message.type" "example.Helloworld\$Response"
              defaultTagsNoPeerService()
            }
          }
        }
      }
      trace(2) {
        span {
          operationName serverOperation()
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          childOf trace(0).get(1)
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "grpc.status.code" "OK"
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
        span {
          operationName "grpc.message"
          resourceName "grpc.message"
          spanType DDSpanTypes.RPC
          childOf span(0)
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "message.type" "example.Helloworld\$Request"
            defaultTags()
          }
        }
      }
    }

    and:
    def traceId = TEST_WRITER[0].traceId.first()
    traceId.toLong() as String == collectedAppSecHeaders['x-datadog-trace-id']
    collectedAppSecReqMsgs.size() == 1
    collectedAppSecReqMsgs.first().name == name
    collectedAppSecServerMethods.size() == 1
    collectedAppSecServerMethods.first() == 'example.Greeter/SayHello'

    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        tags.hasAllTags("direction:out", "type:grpc")
      }

      StatsGroup second = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == first.hash }
      verifyAll(second) {
        tags.hasAllTags("direction:in", "type:grpc")
      }
    }

    cleanup:
    serverRule.stop().get()

    where:
    name << ["some name", "some other name"]
  }

  def "test error - #name"() {
    setup:
    def error = status.asException()
    ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
          sb.service(GrpcService.builder().addService(new GreeterGrpc.GreeterImplBase() {
              @Override
              void sayHello(
                final Helloworld.Request req, final StreamObserver<Helloworld.Response> responseObserver) {
                responseObserver.onError(error)
              }
            }).build())
        }
      }
    serverRule.configure(Server.builder().requestTimeout(timeoutDuration()))
    serverRule.start()

    GreeterGrpc.GreeterBlockingStub client = Clients.builder(serverRule.uri(SessionProtocol.HTTP, GrpcSerializationFormats.PROTO))
      .writeTimeout(timeoutDuration())
      .responseTimeout(timeoutDuration())
      .build(GreeterGrpc.GreeterBlockingStub)

    when:
    client.sayHello(Helloworld.Request.newBuilder().setName(name).build())
    // wait here to make checkpoint asserts deterministic
    TEST_WRITER.waitForTraces(2)

    then:
    thrown StatusRuntimeException

    assertTraces(2) {
      sortSpansByStart()
      trace(1) {
        span {
          operationName clientOperation()
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          parent()
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.RPC_SERVICE" "example.Greeter"
            "grpc.status.code" "${status.code.name()}"
            "status.description" description
            "request.type" "example.Helloworld\$Request"
            "response.type" "example.Helloworld\$Response"
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
      }
      trace(2) {
        span {
          operationName serverOperation()
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          childOf trace(0).get(0)
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "grpc.status.code" "${status.code.name()}"
            "status.description" description
            "canceled" { true } // 1.0.0 handles cancellation incorrectly so accesting any value
            if (status.cause != null) {
              errorTags status.cause.class, status.cause.message
            }
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
        span {
          operationName "grpc.message"
          resourceName "grpc.message"
          spanType DDSpanTypes.RPC
          childOf span(0)
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "message.type" "example.Helloworld\$Request"
            defaultTags()
          }
        }
      }
    }

    cleanup:
    serverRule.stop().get()

    where:
    name                          | status                                                                 | description
    "Runtime - cause"             | Status.UNKNOWN.withCause(new RuntimeException("some error"))           | null
    "Status - cause"              | Status.PERMISSION_DENIED.withCause(new RuntimeException("some error")) | null
    "StatusRuntime - cause"       | Status.UNIMPLEMENTED.withCause(new RuntimeException("some error"))     | null
    "Runtime - description"       | Status.UNKNOWN.withDescription("some description")                     | "some description"
    "Status - description"        | Status.PERMISSION_DENIED.withDescription("some description")           | "some description"
    "StatusRuntime - description" | Status.UNIMPLEMENTED.withDescription("some description")               | "some description"
  }

  def "test error thrown - #name"() {
    setup:

    def error = status.asRuntimeException()
    ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
          sb.service(GrpcService.builder().addService(new GreeterGrpc.GreeterImplBase() {
              @Override
              void sayHello(
                final Helloworld.Request req, final StreamObserver<Helloworld.Response> responseObserver) {
                throw error
              }
            }).build())
        }
      }
    serverRule.configure(Server.builder().requestTimeout(timeoutDuration()))
    serverRule.start()

    GreeterGrpc.GreeterBlockingStub client = Clients.builder(serverRule.uri(SessionProtocol.HTTP, GrpcSerializationFormats.PROTO))
      .writeTimeout(timeoutDuration())
      .responseTimeout(timeoutDuration())
      .build(GreeterGrpc.GreeterBlockingStub)

    when:
    client.sayHello(Helloworld.Request.newBuilder().setName(name).build())
    // wait here to make checkpoint asserts deterministic
    TEST_WRITER.waitForTraces(2)

    then:
    thrown StatusRuntimeException

    assertTraces(2) {
      sortSpansByStart()
      trace(1) {
        span {
          operationName clientOperation()
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          parent()
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.RPC_SERVICE" "example.Greeter"
            "grpc.status.code" status.code.name()
            if (status.description != null) {
              "status.description" status.description
            }
            "request.type" "example.Helloworld\$Request"
            "response.type" "example.Helloworld\$Response"
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
      }
      trace(2) {
        span {
          operationName serverOperation()
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          childOf trace(0).get(0)
          errored errorFlag
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            errorTags error.class, error.message
            "grpc.status.code" "${status.code.name()}"
            "status.description"  { it == null || String}
            "canceled" { true } // 1.0.0 handles cancellation incorrectly so accesting any value
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
        span {
          operationName "grpc.message"
          resourceName "grpc.message"
          spanType DDSpanTypes.RPC
          childOf span(0)
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "message.type" "example.Helloworld\$Request"
            defaultTags()
          }
        }
      }
    }

    cleanup:
    serverRule.stop().get()

    where:
    name                                    | status                                                                  | errorFlag
    "Runtime - cause"                       | Status.UNKNOWN.withCause(new RuntimeException("some error"))            | true
    "Status - cause"                        | Status.PERMISSION_DENIED.withCause(new RuntimeException("some error"))  | true
    "StatusRuntime - cause"                 | Status.UNIMPLEMENTED.withCause(new RuntimeException("some error"))      | true
    "Runtime - description"                 | Status.UNKNOWN.withDescription("some description")                      | true
    "Status - description"                  | Status.PERMISSION_DENIED.withDescription("some description")            | true
    "StatusRuntime - description"           | Status.UNIMPLEMENTED.withDescription("some description")                | true
    "StatusRuntime - Not errored no cause"   | Status.fromCodeValue(15).withDescription("some description")           | false
    "StatusRuntime - Not errored with cause" | Status.fromCodeValue(15).withCause(new RuntimeException("some error")) | false
  }

  def "skip binary headers"() {
    setup:
    def meta = new Metadata()
    meta.put(Metadata.Key.<String> of("test", Metadata.ASCII_STRING_MARSHALLER), "val")
    meta.put(Metadata.Key.<byte[]> of("test-bin", Metadata.BINARY_BYTE_MARSHALLER), "bin-val".bytes)

    when:
    def keys = new ArrayList()
    GrpcExtractAdapter.GETTER.forEachKey(meta, new AgentPropagation.KeyClassifier() {

        @Override
        boolean accept(String key, String value) {
          keys.add(key.toLowerCase())
          return true
        }
      })

    then:
    keys == ["test"]
  }

  def "test ignore ignored methods"() {
    setup:

    ExecutorService responseExecutor = Executors.newSingleThreadExecutor()
    ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
          sb.service(GrpcService.builder().addService(new GreeterGrpc.GreeterImplBase() {
              @Override
              void ignore(
                final Helloworld.Request req, final StreamObserver<Helloworld.Response> responseObserver) {
                final Helloworld.Response reply = Helloworld.Response.newBuilder().setMessage("Hello $req.name").build()
                responseExecutor.execute {
                  responseObserver.onNext(reply)
                  responseObserver.onCompleted()
                }
              }
            }).build())
        }
      }
    serverRule.configure(Server.builder().requestTimeout(timeoutDuration()))
    serverRule.start()

    GreeterGrpc.GreeterBlockingStub client = Clients.builder(serverRule.uri(SessionProtocol.HTTP, GrpcSerializationFormats.PROTO))
      .writeTimeout(timeoutDuration())
      .responseTimeout(timeoutDuration())
      .build(GreeterGrpc.GreeterBlockingStub)

    when:
    def response = runUnderTrace("parent") {
      def resp = client.ignore(Helloworld.Request.newBuilder().setName("whatever").build())
      return resp
    }

    then:
    response.message == "Hello whatever"
    assertTraces(2) {
      sortSpansByStart()
      trace(1) {
        basicSpan(it, "parent")
      }
      trace(2) {
        span {
          operationName serverOperation()
          resourceName "example.Greeter/Ignore"
          spanType DDSpanTypes.RPC
          parentSpanId DDSpanId.ZERO
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "grpc.status.code" "OK"
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            defaultTags(true)
          }
        }
        span {
          operationName "grpc.message"
          resourceName "grpc.message"
          spanType DDSpanTypes.RPC
          childOf span(0)
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "message.type" "example.Helloworld\$Request"
            defaultTags()
          }
        }
      }
    }

    cleanup:
    serverRule.stop()
  }

  def "test ignore ignored inbound methods"() {
    setup:

    ExecutorService responseExecutor = Executors.newSingleThreadExecutor()
    ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
          sb.service(GrpcService.builder().addService(new GreeterGrpc.GreeterImplBase() {
              @Override
              void ignoreInbound(
                final Helloworld.Request req, final StreamObserver<Helloworld.Response> responseObserver) {
                final Helloworld.Response reply = Helloworld.Response.newBuilder().setMessage("Hello $req.name").build()
                responseExecutor.execute {
                  responseObserver.onNext(reply)
                  responseObserver.onCompleted()
                }
              }
            }).build())
        }
      }
    serverRule.configure(Server.builder().requestTimeout(timeoutDuration()))
    serverRule.start()

    GreeterGrpc.GreeterBlockingStub client = Clients.builder(serverRule.uri(SessionProtocol.HTTP, GrpcSerializationFormats.PROTO))
      .writeTimeout(timeoutDuration())
      .responseTimeout(timeoutDuration())
      .build(GreeterGrpc.GreeterBlockingStub)

    when:
    def response = client.ignoreInbound(Helloworld.Request.newBuilder().setName("whatever").build())

    then:
    response.message == "Hello whatever"
    assertTraces(1) {
      sortSpansByStart()
      trace(hasClientMessageSpans() ? 2 : 1) {
        span {
          operationName clientOperation()
          resourceName "example.Greeter/IgnoreInbound"
          spanType DDSpanTypes.RPC
          parent()
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "armeria-grpc-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.RPC_SERVICE" "example.Greeter"
            "grpc.status.code" "OK"
            "request.type" "example.Helloworld\$Request"
            "response.type" "example.Helloworld\$Response"
            if ({ isDataStreamsEnabled() }) {
              "$DDTags.PATHWAY_HASH" { String }
            }
            peerServiceFrom(Tags.RPC_SERVICE)
            defaultTags()
          }
        }
        if (hasClientMessageSpans()) {
          span {
            operationName "grpc.message"
            resourceName "grpc.message"
            spanType DDSpanTypes.RPC
            childOf span(0)
            errored false
            measured true
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

    cleanup:
    serverRule.stop()
  }
}

abstract class ArmeriaGrpcDataStreamsEnabledForkedTest extends ArmeriaGrpcTest {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.data.streams.enabled", "true")
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return true
  }
}

class ArmeriaGrpcDataStreamsEnabledV0Test extends ArmeriaGrpcDataStreamsEnabledForkedTest {

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


class ArmeriaGrpcClientMessagesEnabledTest extends ArmeriaGrpcDataStreamsEnabledV0Test {
  @Override
  protected boolean hasClientMessageSpans() {
    true
  }
}

class ArmeriaGrpcDataStreamsEnabledV1ForkedTest extends ArmeriaGrpcDataStreamsEnabledForkedTest {

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

class ArmeriaGrpcDataStreamsDisabledForkedTest extends ArmeriaGrpcTest {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.data.streams.enabled", "false")
  }

  @Override
  protected boolean isDataStreamsEnabled() {
    return false
  }

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
