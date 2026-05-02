package datadog.trace.instrumentation.sofarpc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests SOFA RPC REST protocol instrumentation.
 *
 * <p>Our instrumentation contributes sofarpc.request[client] (AbstractClusterInstrumentation) and
 * sofarpc.request[server] (RestServerHandlerInstrumentation + ProviderProxyInvokerInstrumentation).
 * Distributed trace propagation is delegated to dd-trace-java's HTTP instrumentation (Apache
 * HttpClient on the client side, Netty on the server side), which is not active in this unit test —
 * so the server span appears as a separate trace root here.
 *
 * <p>The service interface and implementation are defined in top-level classes ({@link
 * RestGreeterService}, {@link RestGreeterServiceImpl}) to avoid classloader shadowing issues:
 * {@code TestClassShadowingExtension} re-loads inner classes of the test class into a fresh
 * classloader, which causes SOFA RPC's in-process REST invocation to fail with {@code object is not
 * an instance of declaring class}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SofaRpcRestTest extends AbstractInstrumentationTest {

  private static final int PORT = 12205;

  private ProviderConfig<RestGreeterService> restProviderConfig;
  private RestGreeterService greeterService;

  @BeforeAll
  void setupServers() {
    restProviderConfig =
        new ProviderConfig<RestGreeterService>()
            .setApplication(new ApplicationConfig().setAppName("test-server"))
            .setInterfaceId(RestGreeterService.class.getName())
            .setRef(new RestGreeterServiceImpl())
            .setServer(new ServerConfig().setProtocol("rest").setHost("127.0.0.1").setPort(PORT))
            .setRegister(false);
    restProviderConfig.export();

    greeterService =
        new ConsumerConfig<RestGreeterService>()
            .setApplication(new ApplicationConfig().setAppName("test-client"))
            .setInterfaceId(RestGreeterService.class.getName())
            .setDirectUrl("rest://127.0.0.1:" + PORT)
            .setProtocol("rest")
            .setRegister(false)
            .setSubscribe(false)
            .refer();
  }

  @AfterAll
  void tearDownServers() {
    if (restProviderConfig != null) {
      restProviderConfig.unExport();
    }
  }

  @Test
  void clientAndServerSpansForRestCall() throws InterruptedException, TimeoutException {
    String serviceUniqueName = RestGreeterService.class.getName() + ":1.0";

    AgentSpan callerSpan = startSpan("caller");
    AgentScope callerScope = activateSpan(callerSpan);
    String reply;
    try {
      reply = greeterService.sayHello("World");
    } finally {
      callerScope.close();
      callerSpan.finish();
    }

    assertEquals("Hello, World", reply);

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan clientSofaSpan = findSpan(allSpans, "sofarpc.request", "client");
    DDSpan serverSofaSpan = findSpan(allSpans, "sofarpc.request", "server");

    // Client span — full service unique name is available on client side
    assertNotNull(clientSofaSpan, "Expected sofarpc client span");
    assertEquals(serviceUniqueName + "/sayHello", clientSofaSpan.getResourceName().toString());
    assertEquals("rest", String.valueOf(clientSofaSpan.getTag("sofarpc.protocol")));
    assertEquals("sofarpc-client", String.valueOf(clientSofaSpan.getTag("component")));
    assertEquals("client", String.valueOf(clientSofaSpan.getTag("span.kind")));
    assertEquals("sofarpc", String.valueOf(clientSofaSpan.getTag("rpc.system")));
    assertEquals("sayHello", String.valueOf(clientSofaSpan.getTag("rpc.method")));
    assertEquals(callerSpan.getSpanId(), clientSofaSpan.getParentId());
    assertFalse(clientSofaSpan.isError());

    // Server span — SofaRequest.getTargetServiceUniqueName() is null on the server side for REST
    // (not propagated through the JAX-RS layer), so resourceName is the method name only
    // and rpc.service tag is absent. Parent link to the client trace is provided by
    // HTTP instrumentation (not active in this test), so this span is a trace root here.
    assertNotNull(serverSofaSpan, "Expected sofarpc server span");
    assertEquals("sayHello", serverSofaSpan.getResourceName().toString());
    assertEquals("rest", String.valueOf(serverSofaSpan.getTag("sofarpc.protocol")));
    assertEquals("sofarpc-server", String.valueOf(serverSofaSpan.getTag("component")));
    assertEquals("server", String.valueOf(serverSofaSpan.getTag("span.kind")));
    assertEquals("sofarpc", String.valueOf(serverSofaSpan.getTag("rpc.system")));
    assertNull(
        serverSofaSpan.getTag("rpc.service"), "rpc.service should be absent for REST server span");
    assertEquals("sayHello", String.valueOf(serverSofaSpan.getTag("rpc.method")));
    assertFalse(serverSofaSpan.isError());
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
          && spanKind.equals(span.getTag("span.kind"))) {
        return span;
      }
    }
    return null;
  }
}
