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
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
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
 * Tests gRPC instrumentation for streaming RPC patterns: server streaming, client streaming, and
 * bidirectional streaming. Verifies span creation, tags, and parent-child relationships across
 * streaming calls.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GrpcStreamingTest extends AbstractInstrumentationTest {

  private static final int PORT = 18882;

  private Server grpcServer;
  private ManagedChannel grpcChannel;
  private GreeterGrpc.GreeterBlockingStub blockingStub;
  private GreeterGrpc.GreeterStub asyncStub;

  @BeforeAll
  void setupGrpc() throws IOException {
    grpcServer =
        ServerBuilder.forPort(PORT)
            .addService(new StreamingGreeterImpl())
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
  void serverStreamingCreatesClientAndServerSpans()
      throws InterruptedException, TimeoutException {
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);
    List<Response> responses = new ArrayList<>();
    try {
      Iterator<Response> iterator =
          blockingStub.serverStreamHello(Request.newBuilder().setName("stream").build());
      while (iterator.hasNext()) {
        responses.add(iterator.next());
      }
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    assertEquals(3, responses.size(), "Expected 3 streamed responses");
    assertEquals("Hello stream 0", responses.get(0).getMessage());
    assertEquals("Hello stream 1", responses.get(1).getMessage());
    assertEquals("Hello stream 2", responses.get(2).getMessage());

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan clientSpan = findSpan(allSpans, "grpc.client", "client");
    assertNotNull(clientSpan, "Expected grpc.client span for server streaming");
    assertEquals(
        "example.Greeter/ServerStreamHello",
        clientSpan.getResourceName().toString(),
        "Resource name should be the server streaming method path");
    assertEquals("grpc", String.valueOf(clientSpan.getTag("component")));
    assertEquals("client", String.valueOf(clientSpan.getTag("span.kind")));
    assertEquals("rpc", clientSpan.getSpanType());
    assertEquals("grpc", String.valueOf(clientSpan.getTag("rpc.system")));
    assertEquals("example.Greeter", String.valueOf(clientSpan.getTag("rpc.service")));
    assertFalse(clientSpan.isError());
    assertEquals(
        parentSpan.getSpanId(),
        clientSpan.getParentId(),
        "Client span should be child of parent");

    DDSpan serverSpan = findSpan(allSpans, "grpc.server", "server");
    assertNotNull(serverSpan, "Expected grpc.server span for server streaming");
    assertEquals(
        "example.Greeter/ServerStreamHello",
        serverSpan.getResourceName().toString(),
        "Server resource name should match the streaming method path");
    assertEquals("grpc", String.valueOf(serverSpan.getTag("component")));
    assertEquals("server", String.valueOf(serverSpan.getTag("span.kind")));
    assertEquals("rpc", serverSpan.getSpanType());
    assertEquals("grpc", String.valueOf(serverSpan.getTag("rpc.system")));
    assertEquals("example.Greeter", String.valueOf(serverSpan.getTag("rpc.service")));
    assertFalse(serverSpan.isError());
    assertEquals(
        clientSpan.getSpanId(),
        serverSpan.getParentId(),
        "Server span should be child of client span");
  }

  @Test
  void clientStreamingCreatesClientAndServerSpans()
      throws InterruptedException, TimeoutException {
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);

    CountDownLatch latch = new CountDownLatch(1);
    CopyOnWriteArrayList<Response> responses = new CopyOnWriteArrayList<>();

    StreamObserver<Request> requestObserver;
    try {
      requestObserver =
          asyncStub.clientStreamHello(
              new StreamObserver<Response>() {
                @Override
                public void onNext(Response value) {
                  responses.add(value);
                }

                @Override
                public void onError(Throwable t) {
                  latch.countDown();
                }

                @Override
                public void onCompleted() {
                  latch.countDown();
                }
              });

      requestObserver.onNext(Request.newBuilder().setName("msg1").build());
      requestObserver.onNext(Request.newBuilder().setName("msg2").build());
      requestObserver.onNext(Request.newBuilder().setName("msg3").build());
      requestObserver.onCompleted();
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Response should arrive within timeout");
    assertEquals(1, responses.size(), "Client streaming should produce a single response");
    assertEquals(
        "Hello msg1, msg2, msg3",
        responses.get(0).getMessage(),
        "Response should aggregate all client messages");

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan clientSpan = findSpan(allSpans, "grpc.client", "client");
    assertNotNull(clientSpan, "Expected grpc.client span for client streaming");
    assertEquals(
        "example.Greeter/ClientStreamHello",
        clientSpan.getResourceName().toString(),
        "Resource name should be the client streaming method path");
    assertEquals("grpc", String.valueOf(clientSpan.getTag("component")));
    assertEquals("rpc", clientSpan.getSpanType());
    assertEquals("grpc", String.valueOf(clientSpan.getTag("rpc.system")));
    assertFalse(clientSpan.isError());

    DDSpan serverSpan = findSpan(allSpans, "grpc.server", "server");
    assertNotNull(serverSpan, "Expected grpc.server span for client streaming");
    assertEquals(
        "example.Greeter/ClientStreamHello",
        serverSpan.getResourceName().toString(),
        "Server resource name should match the client streaming method path");
    assertEquals("grpc", String.valueOf(serverSpan.getTag("component")));
    assertEquals("rpc", serverSpan.getSpanType());
    assertEquals("grpc", String.valueOf(serverSpan.getTag("rpc.system")));
    assertFalse(serverSpan.isError());
    assertEquals(
        clientSpan.getSpanId(),
        serverSpan.getParentId(),
        "Server span should be child of client span");
  }

  @Test
  void bidiStreamingCreatesClientAndServerSpans()
      throws InterruptedException, TimeoutException {
    AgentSpan parentSpan = startSpan("test", "parent");
    AgentScope parentScope = activateSpan(parentSpan);

    CountDownLatch latch = new CountDownLatch(1);
    CopyOnWriteArrayList<Response> responses = new CopyOnWriteArrayList<>();

    StreamObserver<Request> requestObserver;
    try {
      requestObserver =
          asyncStub.bidiStreamHello(
              new StreamObserver<Response>() {
                @Override
                public void onNext(Response value) {
                  responses.add(value);
                }

                @Override
                public void onError(Throwable t) {
                  latch.countDown();
                }

                @Override
                public void onCompleted() {
                  latch.countDown();
                }
              });

      requestObserver.onNext(Request.newBuilder().setName("bidi1").build());
      requestObserver.onNext(Request.newBuilder().setName("bidi2").build());
      requestObserver.onCompleted();
    } finally {
      parentScope.close();
      parentSpan.finish();
    }

    assertTrue(latch.await(10, TimeUnit.SECONDS), "Response should arrive within timeout");
    assertEquals(2, responses.size(), "Bidi streaming should echo each request as a response");
    assertEquals("Hello bidi1", responses.get(0).getMessage());
    assertEquals("Hello bidi2", responses.get(1).getMessage());

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan clientSpan = findSpan(allSpans, "grpc.client", "client");
    assertNotNull(clientSpan, "Expected grpc.client span for bidi streaming");
    assertEquals(
        "example.Greeter/BidiStreamHello",
        clientSpan.getResourceName().toString(),
        "Resource name should be the bidi streaming method path");
    assertEquals("grpc", String.valueOf(clientSpan.getTag("component")));
    assertEquals("client", String.valueOf(clientSpan.getTag("span.kind")));
    assertEquals("rpc", clientSpan.getSpanType());
    assertEquals("grpc", String.valueOf(clientSpan.getTag("rpc.system")));
    assertEquals("example.Greeter", String.valueOf(clientSpan.getTag("rpc.service")));
    assertFalse(clientSpan.isError());

    DDSpan serverSpan = findSpan(allSpans, "grpc.server", "server");
    assertNotNull(serverSpan, "Expected grpc.server span for bidi streaming");
    assertEquals(
        "example.Greeter/BidiStreamHello",
        serverSpan.getResourceName().toString(),
        "Server resource name should match the bidi streaming method path");
    assertEquals("grpc", String.valueOf(serverSpan.getTag("component")));
    assertEquals("server", String.valueOf(serverSpan.getTag("span.kind")));
    assertEquals("rpc", serverSpan.getSpanType());
    assertEquals("grpc", String.valueOf(serverSpan.getTag("rpc.system")));
    assertEquals("example.Greeter", String.valueOf(serverSpan.getTag("rpc.service")));
    assertFalse(serverSpan.isError());
    assertEquals(
        clientSpan.getSpanId(),
        serverSpan.getParentId(),
        "Server span should be child of client span");
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

  static class StreamingGreeterImpl extends GreeterGrpc.GreeterImplBase {
    @Override
    public void serverStreamHello(Request request, StreamObserver<Response> responseObserver) {
      for (int i = 0; i < 3; i++) {
        responseObserver.onNext(
            Response.newBuilder()
                .setMessage("Hello " + request.getName() + " " + i)
                .build());
      }
      responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<Request> clientStreamHello(StreamObserver<Response> responseObserver) {
      List<String> names = new ArrayList<>();
      return new StreamObserver<Request>() {
        @Override
        public void onNext(Request value) {
          names.add(value.getName());
        }

        @Override
        public void onError(Throwable t) {
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onNext(
              Response.newBuilder()
                  .setMessage("Hello " + String.join(", ", names))
                  .build());
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public StreamObserver<Request> bidiStreamHello(StreamObserver<Response> responseObserver) {
      return new StreamObserver<Request>() {
        @Override
        public void onNext(Request value) {
          responseObserver.onNext(
              Response.newBuilder().setMessage("Hello " + value.getName()).build());
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
