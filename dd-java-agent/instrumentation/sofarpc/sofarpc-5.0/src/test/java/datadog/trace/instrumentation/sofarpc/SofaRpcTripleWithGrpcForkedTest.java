package datadog.trace.instrumentation.sofarpc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.alipay.sofa.rpc.config.ApplicationConfig;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.config.ProviderConfig;
import com.alipay.sofa.rpc.config.ServerConfig;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Forked test: runs in an isolated JVM with gRPC instrumentation ENABLED (the default).
 *
 * <p>When gRPC instrumentation is active, SOFA RPC Triple calls produce both gRPC spans
 * (grpc.client / grpc.server) and sofarpc spans. All spans run in-process and share one trace.
 * Expected hierarchy:
 *
 * <pre>
 *   caller
 *     └─ sofarpc.request [client]
 *          └─ grpc.client
 *               └─ grpc.server
 *                    └─ sofarpc.request [server]   ← asserted below
 * </pre>
 *
 * <p>The key assertion is that sofarpc.request[server] is a direct child of grpc.server, NOT of
 * grpc.client. Before the fix, TripleServerInstrumentation would extract the parent context from
 * gRPC Metadata regardless of whether a grpc.server span was active, which caused
 * sofarpc.request[server] to become a sibling of grpc.server instead.
 *
 * <p>Server setup uses {@code @BeforeEach} (not {@code @BeforeAll}) so the gRPC server is built
 * AFTER the superclass {@code init()} installs the ByteBuddy agent. This ensures that {@code
 * GrpcServerBuilderInstrumentation} can add {@code TracingServerInterceptor} to the server at build
 * time, which is required for grpc.server spans to be produced.
 *
 * <p>The service interface ({@link TripleGreeterService}) and implementation ({@link
 * TripleGreeterServiceImpl}) are defined as top-level classes to avoid classloader shadowing: the
 * {@code TestClassShadowingExtension} re-loads inner classes of the test into a fresh classloader,
 * which would cause SOFA RPC's instanceof check to fail when the context classloader is the
 * shadowing classloader.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SofaRpcTripleWithGrpcForkedTest extends AbstractInstrumentationTest {
  // No configurePreAgent() override — gRPC instrumentation is enabled by default.

  private static final int TRIPLE_PORT = 12204;

  private ProviderConfig<TripleGreeterService> tripleProviderConfig;
  private TripleGreeterService greeterService;

  // @BeforeEach (not @BeforeAll) so the gRPC server is built after the agent is installed by
  // AbstractInstrumentationTest.init(), allowing GrpcServerBuilderInstrumentation to add
  // TracingServerInterceptor at build time.
  @BeforeEach
  void setupServer() {
    tripleProviderConfig =
        new ProviderConfig<TripleGreeterService>()
            .setApplication(new ApplicationConfig().setAppName("test-server"))
            .setInterfaceId(TripleGreeterService.class.getName())
            .setRef(new TripleGreeterServiceImpl())
            .setServer(
                new ServerConfig().setProtocol("tri").setHost("127.0.0.1").setPort(TRIPLE_PORT))
            .setRegister(false);
    tripleProviderConfig.export();

    greeterService =
        new ConsumerConfig<TripleGreeterService>()
            .setApplication(new ApplicationConfig().setAppName("test-client"))
            .setInterfaceId(TripleGreeterService.class.getName())
            .setDirectUrl("tri://127.0.0.1:" + TRIPLE_PORT)
            .setProtocol("tri")
            .setRegister(false)
            .setSubscribe(false)
            .refer();
  }

  @AfterEach
  void tearDownServer() {
    if (tripleProviderConfig != null) {
      tripleProviderConfig.unExport();
      tripleProviderConfig = null;
    }
    greeterService = null;
  }

  @Test
  void tripleServerSpanIsNestedUnderGrpcServer() throws InterruptedException, TimeoutException {
    AgentSpan callerSpan = startSpan("test", "caller");
    AgentScope callerScope = activateSpan(callerSpan);
    String reply;
    try {
      reply = greeterService.sayHello("World");
    } finally {
      callerScope.close();
      callerSpan.finish();
    }

    assertEquals("Hello, World", reply);

    // Client spans (caller, sofarpc[client], grpc.client) are flushed when the client-side
    // root span finishes. Server spans (grpc.server, grpc.message, sofarpc[server]) are flushed
    // when grpc.server — the server-side local root — finishes on its own thread.
    // That gives two separate ListWriter entries even though both share the same trace_id.
    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan serverSofaSpan = findSpan(allSpans, "sofarpc.request", "server");
    DDSpan grpcServerSpan = findSpan(allSpans, "grpc.server", null);

    assertNotNull(
        serverSofaSpan, "Expected sofarpc[server] span. Spans found: " + describeSpans(allSpans));
    assertNotNull(
        grpcServerSpan, "Expected grpc.server span. Spans found: " + describeSpans(allSpans));

    // sofarpc.request[server] must be a direct child of grpc.server, not grpc.client
    assertEquals(
        grpcServerSpan.getSpanId(),
        serverSofaSpan.getParentId(),
        "sofarpc.request[server] should be a child of grpc.server");
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
      if (span.getOperationName().toString().equals(operationName)) {
        if (spanKind == null || spanKind.equals(span.getTag("span.kind"))) {
          return span;
        }
      }
    }
    return null;
  }

  private String describeSpans(List<DDSpan> spans) {
    List<String> descriptions = new ArrayList<>();
    for (DDSpan span : spans) {
      descriptions.add(span.getOperationName() + "[" + span.getTag("span.kind") + "]");
    }
    return descriptions.toString();
  }
}
