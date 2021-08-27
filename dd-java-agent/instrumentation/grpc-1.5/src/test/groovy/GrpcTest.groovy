import com.google.common.util.concurrent.MoreExecutors
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.api.DDId
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.grpc.server.GrpcExtractAdapter
import example.GreeterGrpc
import example.Helloworld
import io.grpc.BindableService
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.Checkpointer.CPU
import static datadog.trace.api.Checkpointer.END
import static datadog.trace.api.Checkpointer.SPAN
import static datadog.trace.api.Checkpointer.THREAD_MIGRATION

class GrpcTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.trace.grpc.ignored.outbound.methods", "example.Greeter/Ignore")
  }

  def "test request-response"() {
    setup:
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    ExecutorService responseExecutor = Executors.newSingleThreadExecutor()
    BindableService greeter = new GreeterGrpc.GreeterImplBase() {
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
      }
    def builder = InProcessServerBuilder.forName(getClass().name).addService(greeter).executor(executor)
    (0..extraBuildCalls).each { builder.build() }
    Server server = builder.build().start()

    ManagedChannel channel = InProcessChannelBuilder.forName(getClass().name).build()
    GreeterGrpc.GreeterBlockingStub client = GreeterGrpc.newBlockingStub(channel)

    when:
    def response = runUnderTrace("parent") {
      def resp = client.sayHello(Helloworld.Request.newBuilder().setName(name).build())
      return resp
    }
    // wait here to make checkpoint asserts deterministic
    TEST_WRITER.waitForTraces(2)

    then:
    response.message == "Hello $name"
    assertTraces(2) {
      trace(3) {
        basicSpan(it, "parent")
        span {
          operationName "grpc.client"
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          childOf span(0)
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "grpc-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "status.code" "OK"
            "request.type" "example.Helloworld\$Request"
            "response.type" "example.Helloworld\$Response"
            defaultTags()
          }
        }
        span {
          operationName "grpc.message"
          resourceName "grpc.message"
          spanType DDSpanTypes.RPC
          childOf span(1)
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "grpc-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "message.type" "example.Helloworld\$Response"
            defaultTags()
          }
        }
      }
      trace(2) {
        span {
          operationName "grpc.server"
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          childOf trace(0).get(1)
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "status.code" "OK"
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
            "$Tags.COMPONENT" "grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "message.type" "example.Helloworld\$Request"
            defaultTags()
          }
        }
      }
    }
    5 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    5 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.checkpoint(_, THREAD_MIGRATION)
    _ * TEST_CHECKPOINTER.checkpoint(_, THREAD_MIGRATION | END)
    _ * TEST_CHECKPOINTER.checkpoint(_, CPU | END)
    _ * TEST_CHECKPOINTER.onRootSpan(_, _)
    0 * TEST_CHECKPOINTER._

    cleanup:
    channel?.shutdownNow()?.awaitTermination(10, TimeUnit.SECONDS)
    server?.shutdownNow()?.awaitTermination()
    if (executor instanceof ExecutorService) {
      (executor as ExecutorService).shutdownNow()
    }

    where:
    name              | executor                            | extraBuildCalls
    "some name"       | MoreExecutors.directExecutor()      | 0
    "some other name" | MoreExecutors.directExecutor()      | 0
    "some name"       | newWorkStealingPool()               | 0
    "some other name" | newWorkStealingPool()               | 0
    "some name"       | Executors.newSingleThreadExecutor() | 0
    "some other name" | Executors.newSingleThreadExecutor() | 0
    "some name"       | MoreExecutors.directExecutor()      | 1
    "some other name" | MoreExecutors.directExecutor()      | 1
    "some name"       | newWorkStealingPool()               | 1
    "some other name" | newWorkStealingPool()               | 1
    "some name"       | Executors.newSingleThreadExecutor() | 1
    "some other name" | Executors.newSingleThreadExecutor() | 1
  }

  def "test error - #name"() {
    setup:
    def error = status.asException()
    BindableService greeter = new GreeterGrpc.GreeterImplBase() {
        @Override
        void sayHello(
          final Helloworld.Request req, final StreamObserver<Helloworld.Response> responseObserver) {
          responseObserver.onError(error)
        }
      }
    Server server = InProcessServerBuilder.forName(getClass().name).addService(greeter).directExecutor().build().start()

    ManagedChannel channel = InProcessChannelBuilder.forName(getClass().name).build()
    GreeterGrpc.GreeterBlockingStub client = GreeterGrpc.newBlockingStub(channel)

    when:
    client.sayHello(Helloworld.Request.newBuilder().setName(name).build())
    // wait here to make checkpoint asserts deterministic
    TEST_WRITER.waitForTraces(2)

    then:
    thrown StatusRuntimeException

    assertTraces(2) {
      trace(1) {
        span {
          operationName "grpc.client"
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          parent()
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "grpc-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "status.code" "${status.code.name()}"
            "status.description" description
            "request.type" "example.Helloworld\$Request"
            "response.type" "example.Helloworld\$Response"
            defaultTags()
          }
        }
      }
      trace(2) {
        span {
          operationName "grpc.server"
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          childOf trace(0).get(0)
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "status.code" "${status.code.name()}"
            "status.description" description
            if (status.cause != null) {
              errorTags status.cause.class, status.cause.message
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
            "$Tags.COMPONENT" "grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "message.type" "example.Helloworld\$Request"
            defaultTags()
          }
        }
      }
    }

    3 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    3 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpan(_, _)
    0 * TEST_CHECKPOINTER._

    cleanup:
    channel?.shutdownNow()?.awaitTermination(10, TimeUnit.SECONDS)
    server?.shutdownNow()?.awaitTermination()

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
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    def error = status.asRuntimeException()
    BindableService greeter = new GreeterGrpc.GreeterImplBase() {
        @Override
        void sayHello(
          final Helloworld.Request req, final StreamObserver<Helloworld.Response> responseObserver) {
          throw error
        }
      }
    Server server = InProcessServerBuilder.forName(getClass().name).addService(greeter).directExecutor().build().start()

    ManagedChannel channel = InProcessChannelBuilder.forName(getClass().name).build()
    GreeterGrpc.GreeterBlockingStub client = GreeterGrpc.newBlockingStub(channel)

    when:
    client.sayHello(Helloworld.Request.newBuilder().setName(name).build())
    // wait here to make checkpoint asserts deterministic
    TEST_WRITER.waitForTraces(2)

    then:
    thrown StatusRuntimeException

    assertTraces(2) {
      trace(1) {
        span {
          operationName "grpc.client"
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          parent()
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "grpc-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "status.code" "UNKNOWN"
            "request.type" "example.Helloworld\$Request"
            "response.type" "example.Helloworld\$Response"
            defaultTags()
          }
        }
      }
      trace(2) {
        span {
          operationName "grpc.server"
          resourceName "example.Greeter/SayHello"
          spanType DDSpanTypes.RPC
          childOf trace(0).get(0)
          errored true
          measured true
          tags {
            "$Tags.COMPONENT" "grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            errorTags error.class, error.message
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
            "$Tags.COMPONENT" "grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "message.type" "example.Helloworld\$Request"
            defaultTags()
          }
        }
      }
    }

    3 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    3 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpan(_, _)
    0 * TEST_CHECKPOINTER._

    cleanup:
    channel?.shutdownNow()?.awaitTermination(10, TimeUnit.SECONDS)
    server?.shutdownNow()?.awaitTermination()

    where:
    name                          | status
    "Runtime - cause"             | Status.UNKNOWN.withCause(new RuntimeException("some error"))
    "Status - cause"              | Status.PERMISSION_DENIED.withCause(new RuntimeException("some error"))
    "StatusRuntime - cause"       | Status.UNIMPLEMENTED.withCause(new RuntimeException("some error"))
    "Runtime - description"       | Status.UNKNOWN.withDescription("some description")
    "Status - description"        | Status.PERMISSION_DENIED.withDescription("some description")
    "StatusRuntime - description" | Status.UNIMPLEMENTED.withDescription("some description")
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
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.THREAD_SEQUENCE)

    ExecutorService responseExecutor = Executors.newSingleThreadExecutor()
    BindableService greeter = new GreeterGrpc.GreeterImplBase() {
        @Override
        void ignore(
          final Helloworld.Request req, final StreamObserver<Helloworld.Response> responseObserver) {
          final Helloworld.Response reply = Helloworld.Response.newBuilder().setMessage("Hello $req.name").build()
          responseExecutor.execute {
            responseObserver.onNext(reply)
            responseObserver.onCompleted()
          }
        }
      }
    Server server = InProcessServerBuilder.forName(getClass().name).addService(greeter).directExecutor().build().start()
    ManagedChannel channel = InProcessChannelBuilder.forName(getClass().name).build()
    GreeterGrpc.GreeterBlockingStub client = GreeterGrpc.newBlockingStub(channel)

    when:
    def response = runUnderTrace("parent") {
      def resp = client.ignore(Helloworld.Request.newBuilder().setName("whatever").build())
      return resp
    }

    then:
    response.message == "Hello whatever"
    assertTraces(2) {
      trace(1) {
        basicSpan(it, "parent")
      }
      trace(2) {
        span {
          operationName "grpc.server"
          resourceName "example.Greeter/Ignore"
          spanType DDSpanTypes.RPC
          parentDDId DDId.ZERO
          errored false
          measured true
          tags {
            "$Tags.COMPONENT" "grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "status.code" "OK"
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
            "$Tags.COMPONENT" "grpc-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "message.type" "example.Helloworld\$Request"
            defaultTags()
          }
        }
      }
    }

    cleanup:
    channel?.shutdownNow()?.awaitTermination(10, TimeUnit.SECONDS)
    server?.shutdownNow()?.awaitTermination()
  }


  def newWorkStealingPool() {
    // Executors.newWorkStealingPool() not available in JDK7
    return new ForkJoinPool
      (Runtime.getRuntime().availableProcessors(),
      ForkJoinPool.defaultForkJoinWorkerThreadFactory,
      null, true)
  }
}
