package datadog.trace.instrumentation.grpc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import example.GreeterGrpc;
import example.HelloworldProto.Request;
import example.HelloworldProto.Response;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests gRPC instrumentation error handling. Verifies that error tags (error.type, error.message,
 * error.stack) and status codes are correctly set on spans when RPCs fail with various gRPC status
 * codes and server-side exceptions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GrpcErrorTest extends AbstractInstrumentationTest {

  private static final int PORT = 18883;

  private Server grpcServer;
  private ManagedChannel grpcChannel;
  private GreeterGrpc.GreeterBlockingStub blockingStub;
  private GreeterGrpc.GreeterStub asyncStub;

  @BeforeAll
  void setupGrpc() throws IOException {
    grpcServer =
        ServerBuilder.forPort(PORT)
            .addService(new ErrorGreeterImpl())
            .build()
            .start();

    grpcChannel =
        ManagedChannelBuilder.forAddress("localhost", PORT).usePlaintext().build();
    blockingStub = GreeterGrpc.newBlockingStub(grpcChannel);
    asyncStub = GreeterGrpc.newStub(grpcChannel);
  }

  @AfterAll
  void tearDownGrpc() throws InterruptedException {
    if (grpcChannel != null) {
      grpcChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    if (grpcServer != null) {
      grpcServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void unaryErrorSetsErrorTagsOnSpans() throws InterruptedException, TimeoutException {
    StatusRuntimeException thrown =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                blockingStub.sayHello(
                    Request.newBuilder().setName("UNIMPLEMENTED").build()));

    assertEquals(
        Status.UNIMPLEMENTED.getCode(),
        thrown.getStatus().getCode(),
        "Exception should carry UNIMPLEMENTED status");

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan clientSpan = findSpan(allSpans, "grpc.client", "client");
    assertNotNull(clientSpan, "Expected grpc.client span on error path");
    assertEquals(
        "example.Greeter/SayHello",
        clientSpan.getResourceName().toString(),
        "Resource name should still reflect the called method");
    assertEquals("grpc", String.valueOf(clientSpan.getTag("component")));
    assertEquals("client", String.valueOf(clientSpan.getTag("span.kind")));
    assertEquals("rpc", clientSpan.getSpanType());
    assertEquals("grpc", String.valueOf(clientSpan.getTag("rpc.system")));
    assertEquals("example.Greeter", String.valueOf(clientSpan.getTag("rpc.service")));
    assertTrue(clientSpan.isError(), "Client span should be marked as errored");
    assertEquals(
        String.valueOf(Status.UNIMPLEMENTED.getCode().value()),
        String.valueOf(clientSpan.getTag("status.code")),
        "Client status code should be UNIMPLEMENTED");
    assertNotNull(
        clientSpan.getTag("error.message"),
        "Client span should have error.message tag");

    DDSpan serverSpan = findSpan(allSpans, "grpc.server", "server");
    assertNotNull(serverSpan, "Expected grpc.server span on error path");
    assertEquals(
        "example.Greeter/SayHello",
        serverSpan.getResourceName().toString(),
        "Server resource name should match the called method");
    assertEquals("grpc", String.valueOf(serverSpan.getTag("component")));
    assertEquals("server", String.valueOf(serverSpan.getTag("span.kind")));
    assertEquals("rpc", serverSpan.getSpanType());
    assertEquals("grpc", String.valueOf(serverSpan.getTag("rpc.system")));
    assertEquals("example.Greeter", String.valueOf(serverSpan.getTag("rpc.service")));
    assertEquals(
        String.valueOf(Status.UNIMPLEMENTED.getCode().value()),
        String.valueOf(serverSpan.getTag("status.code")),
        "Server status code should be UNIMPLEMENTED");
  }

  @Test
  void serverExceptionSetsErrorTagsWithDetails() throws InterruptedException, TimeoutException {
    StatusRuntimeException thrown =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                blockingStub.sayHello(
                    Request.newBuilder().setName("INTERNAL_ERROR").build()));

    assertEquals(
        Status.INTERNAL.getCode(),
        thrown.getStatus().getCode(),
        "Exception should carry INTERNAL status");

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan clientSpan = findSpan(allSpans, "grpc.client", "client");
    assertNotNull(clientSpan, "Expected grpc.client span for internal error");
    assertTrue(clientSpan.isError(), "Client span should be errored on INTERNAL status");
    assertEquals(
        String.valueOf(Status.INTERNAL.getCode().value()),
        String.valueOf(clientSpan.getTag("status.code")),
        "Client status code should be INTERNAL");
    assertNotNull(
        clientSpan.getTag("error.message"),
        "Client span should have error.message for INTERNAL error");

    DDSpan serverSpan = findSpan(allSpans, "grpc.server", "server");
    assertNotNull(serverSpan, "Expected grpc.server span for internal error");
    assertEquals(
        "example.Greeter/SayHello",
        serverSpan.getResourceName().toString());
    assertEquals(
        String.valueOf(Status.INTERNAL.getCode().value()),
        String.valueOf(serverSpan.getTag("status.code")),
        "Server status code should be INTERNAL");
    assertNotNull(
        serverSpan.getTag("error.message"),
        "Server span should have error.message for INTERNAL error");
  }

  @Test
  void notFoundStatusSetsCorrectStatusCode() throws InterruptedException, TimeoutException {
    StatusRuntimeException thrown =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                blockingStub.sayHello(
                    Request.newBuilder().setName("NOT_FOUND").build()));

    assertEquals(
        Status.NOT_FOUND.getCode(),
        thrown.getStatus().getCode(),
        "Exception should carry NOT_FOUND status");

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan clientSpan = findSpan(allSpans, "grpc.client", "client");
    assertNotNull(clientSpan, "Expected grpc.client span for NOT_FOUND");
    assertEquals(
        "example.Greeter/SayHello",
        clientSpan.getResourceName().toString());
    assertEquals("grpc", String.valueOf(clientSpan.getTag("component")));
    assertEquals("rpc", clientSpan.getSpanType());
    assertEquals(
        String.valueOf(Status.NOT_FOUND.getCode().value()),
        String.valueOf(clientSpan.getTag("status.code")),
        "Client status code should be NOT_FOUND");

    DDSpan serverSpan = findSpan(allSpans, "grpc.server", "server");
    assertNotNull(serverSpan, "Expected grpc.server span for NOT_FOUND");
    assertEquals(
        String.valueOf(Status.NOT_FOUND.getCode().value()),
        String.valueOf(serverSpan.getTag("status.code")),
        "Server status code should be NOT_FOUND");
  }

  @Test
  void streamingErrorSetsErrorTagsOnSpans() throws InterruptedException, TimeoutException {
    CountDownLatch latch = new CountDownLatch(1);
    CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

    StreamObserver<Request> requestObserver =
        asyncStub.clientStreamHello(
            new StreamObserver<Response>() {
              @Override
              public void onNext(Response value) {}

              @Override
              public void onError(Throwable t) {
                errors.add(t);
                latch.countDown();
              }

              @Override
              public void onCompleted() {
                latch.countDown();
              }
            });

    requestObserver.onNext(Request.newBuilder().setName("STREAM_ERROR").build());
    requestObserver.onCompleted();

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Error callback should arrive within timeout");
    assertEquals(1, errors.size(), "Expected one error from streaming call");
    assertTrue(
        errors.get(0) instanceof StatusRuntimeException,
        "Error should be StatusRuntimeException");
    assertEquals(
        Status.ABORTED.getCode(),
        ((StatusRuntimeException) errors.get(0)).getStatus().getCode(),
        "Error status should be ABORTED");

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan clientSpan = findSpan(allSpans, "grpc.client", "client");
    assertNotNull(clientSpan, "Expected grpc.client span for streaming error");
    assertEquals(
        "example.Greeter/ClientStreamHello",
        clientSpan.getResourceName().toString(),
        "Resource should be the streaming method path");
    assertTrue(clientSpan.isError(), "Client span should be errored on streaming failure");
    assertEquals("grpc", String.valueOf(clientSpan.getTag("component")));
    assertEquals("rpc", clientSpan.getSpanType());

    DDSpan serverSpan = findSpan(allSpans, "grpc.server", "server");
    assertNotNull(serverSpan, "Expected grpc.server span for streaming error");
    assertEquals(
        "example.Greeter/ClientStreamHello",
        serverSpan.getResourceName().toString());
    assertEquals(
        String.valueOf(Status.ABORTED.getCode().value()),
        String.valueOf(serverSpan.getTag("status.code")),
        "Server status code should be ABORTED");
  }

  private List<DDSpan> flattenTraces() {
    List<DDSpan> result = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      result.addAll(trace);
    }
    return result;
  }

  private DDSpan findSpan(List<DDSpan> spans, String operationName, String spanKind) {
    for (DDSpan span : spans) {
      if (span.getOperationName().toString().equals(operationName)
          && spanKind.equals(String.valueOf(span.getTag("span.kind")))) {
        return span;
      }
    }
    return null;
  }

  static class ErrorGreeterImpl extends GreeterGrpc.GreeterImplBase {
    @Override
    public void sayHello(Request request, StreamObserver<Response> responseObserver) {
      String name = request.getName();
      switch (name) {
        case "UNIMPLEMENTED":
          responseObserver.onError(
              Status.UNIMPLEMENTED
                  .withDescription("Method not implemented")
                  .asRuntimeException());
          break;
        case "INTERNAL_ERROR":
          responseObserver.onError(
              Status.INTERNAL
                  .withDescription("Internal server error")
                  .asRuntimeException());
          break;
        case "NOT_FOUND":
          responseObserver.onError(
              Status.NOT_FOUND
                  .withDescription("Resource not found")
                  .asRuntimeException());
          break;
        default:
          responseObserver.onNext(
              Response.newBuilder().setMessage("Hello " + name).build());
          responseObserver.onCompleted();
          break;
      }
    }

    @Override
    public StreamObserver<Request> clientStreamHello(StreamObserver<Response> responseObserver) {
      return new StreamObserver<Request>() {
        @Override
        public void onNext(Request value) {
          if ("STREAM_ERROR".equals(value.getName())) {
            responseObserver.onError(
                Status.ABORTED
                    .withDescription("Stream aborted by server")
                    .asRuntimeException());
          }
        }

        @Override
        public void onError(Throwable t) {
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }
  }
}
