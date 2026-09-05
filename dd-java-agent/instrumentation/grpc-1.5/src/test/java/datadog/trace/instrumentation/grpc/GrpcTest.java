package datadog.trace.instrumentation.grpc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests gRPC client and server instrumentation for unary RPC calls. Verifies that spans are created
 * with correct operation names, resource names, tags, and parent-child relationships for both client
 * and server sides.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GrpcTest extends AbstractInstrumentationTest {

  private static final int PORT = 18881;

  private Server grpcServer;
  private ManagedChannel grpcChannel;
  private GreeterGrpc.GreeterBlockingStub blockingStub;

  @BeforeAll
  void setupGrpc() throws IOException {
    grpcServer =
        ServerBuilder.forPort(PORT)
            .addService(new GreeterImpl())
            .build()
            .start();

    grpcChannel =
        ManagedChannelBuilder.forAddress("localhost", PORT).usePlaintext().build();
    blockingStub = GreeterGrpc.newBlockingStub(grpcChannel);
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
  void unaryCallCreatesClientAndServerSpans() throws InterruptedException, TimeoutException {
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    Response response;
    try {
      response = blockingStub.sayHello(Request.newBuilder().setName("World").build());
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    assertEquals("Hello World", response.getMessage());

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan clientSpan = findSpan(allSpans, "grpc.client", "client");
    assertNotNull(clientSpan, "Expected grpc.client span");
    assertEquals(
        "example.Greeter/SayHello",
        clientSpan.getResourceName().toString(),
        "Resource name should be the full gRPC method path");
    assertEquals("grpc", String.valueOf(clientSpan.getTag("component")));
    assertEquals("client", String.valueOf(clientSpan.getTag("span.kind")));
    assertEquals("rpc", clientSpan.getSpanType());
    assertEquals(
        "example.Greeter/SayHello", String.valueOf(clientSpan.getTag("rpc.method")));
    assertEquals("grpc", String.valueOf(clientSpan.getTag("rpc.system")));
    assertEquals("example.Greeter", String.valueOf(clientSpan.getTag("rpc.service")));
    assertEquals(
        parentSpan.getSpanId(),
        clientSpan.getParentId(),
        "Client span should be child of parent");
    assertFalse(clientSpan.isError(), "Client span should not be errored on success");
    assertEquals(
        String.valueOf(Status.OK.getCode().value()),
        String.valueOf(clientSpan.getTag("status.code")),
        "Status code should be OK (0)");

    DDSpan serverSpan = findSpan(allSpans, "grpc.server", "server");
    assertNotNull(serverSpan, "Expected grpc.server span");
    assertEquals(
        "example.Greeter/SayHello",
        serverSpan.getResourceName().toString(),
        "Server resource name should match the gRPC method path");
    assertEquals("grpc", String.valueOf(serverSpan.getTag("component")));
    assertEquals("server", String.valueOf(serverSpan.getTag("span.kind")));
    assertEquals("rpc", serverSpan.getSpanType());
    assertEquals(
        "example.Greeter/SayHello", String.valueOf(serverSpan.getTag("rpc.method")));
    assertEquals("grpc", String.valueOf(serverSpan.getTag("rpc.system")));
    assertEquals("example.Greeter", String.valueOf(serverSpan.getTag("rpc.service")));
    assertEquals(
        clientSpan.getSpanId(),
        serverSpan.getParentId(),
        "Server span should be child of client span (distributed trace)");
    assertFalse(serverSpan.isError(), "Server span should not be errored on success");
    assertEquals(
        String.valueOf(Status.OK.getCode().value()),
        String.valueOf(serverSpan.getTag("status.code")),
        "Server status code should be OK (0)");
  }

  @Test
  void traceContextPropagatesFromClientToServer() throws InterruptedException, TimeoutException {
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    try {
      blockingStub.sayHello(Request.newBuilder().setName("propagation-test").build());
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan clientSpan = findSpan(allSpans, "grpc.client", "client");
    DDSpan serverSpan = findSpan(allSpans, "grpc.server", "server");

    assertNotNull(clientSpan, "Expected grpc.client span");
    assertNotNull(serverSpan, "Expected grpc.server span");

    assertEquals(
        parentSpan.getTraceId(),
        clientSpan.getTraceId(),
        "Client span should share trace ID with parent");
    assertEquals(
        clientSpan.getTraceId(),
        serverSpan.getTraceId(),
        "Server span should share trace ID with client (distributed trace)");
    assertEquals(
        clientSpan.getSpanId(),
        serverSpan.getParentId(),
        "Server span parent should be the client span");
  }

  @Test
  void multipleUnaryCallsProduceSeparateTraces() throws InterruptedException, TimeoutException {
    blockingStub.sayHello(Request.newBuilder().setName("first").build());
    blockingStub.sayHello(Request.newBuilder().setName("second").build());

    writer.waitForTraces(4);

    int clientSpanCount = 0;
    int serverSpanCount = 0;
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if ("grpc.client".equals(span.getOperationName().toString())) {
          clientSpanCount++;
        }
        if ("grpc.server".equals(span.getOperationName().toString())) {
          serverSpanCount++;
        }
      }
    }

    assertEquals(2, clientSpanCount, "Expected 2 client spans for 2 RPC calls");
    assertEquals(2, serverSpanCount, "Expected 2 server spans for 2 RPC calls");
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

  static class GreeterImpl extends GreeterGrpc.GreeterImplBase {
    @Override
    public void sayHello(Request request, StreamObserver<Response> responseObserver) {
      responseObserver.onNext(
          Response.newBuilder().setMessage("Hello " + request.getName()).build());
      responseObserver.onCompleted();
    }
  }
}
