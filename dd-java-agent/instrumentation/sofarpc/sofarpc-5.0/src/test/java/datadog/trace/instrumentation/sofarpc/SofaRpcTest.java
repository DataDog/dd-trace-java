package datadog.trace.instrumentation.sofarpc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SofaRpcTest extends AbstractInstrumentationTest {

  private static final int PORT = 12201;
  private static final int ERROR_PORT = 12202;

  private ProviderConfig<GreeterService> providerConfig;
  private ProviderConfig<FaultyService> errorProviderConfig;
  private GreeterService greeterService;
  private FaultyService faultyService;

  @BeforeAll
  void setupServers() {
    ApplicationConfig appConfig = new ApplicationConfig().setAppName("test-server");

    providerConfig =
        new ProviderConfig<GreeterService>()
            .setApplication(appConfig)
            .setInterfaceId(GreeterService.class.getName())
            .setRef(new GreeterServiceImpl())
            .setServer(new ServerConfig().setProtocol("bolt").setHost("127.0.0.1").setPort(PORT))
            .setRegister(false);
    providerConfig.export();

    greeterService =
        new ConsumerConfig<GreeterService>()
            .setApplication(new ApplicationConfig().setAppName("test-client"))
            .setInterfaceId(GreeterService.class.getName())
            .setDirectUrl("bolt://127.0.0.1:" + PORT)
            .setProtocol("bolt")
            .setRegister(false)
            .setSubscribe(false)
            .refer();

    errorProviderConfig =
        new ProviderConfig<FaultyService>()
            .setApplication(appConfig)
            .setInterfaceId(FaultyService.class.getName())
            .setRef(new FaultyServiceImpl())
            .setServer(
                new ServerConfig().setProtocol("bolt").setHost("127.0.0.1").setPort(ERROR_PORT))
            .setRegister(false);
    errorProviderConfig.export();

    faultyService =
        new ConsumerConfig<FaultyService>()
            .setApplication(new ApplicationConfig().setAppName("test-client"))
            .setInterfaceId(FaultyService.class.getName())
            .setDirectUrl("bolt://127.0.0.1:" + ERROR_PORT)
            .setProtocol("bolt")
            .setRegister(false)
            .setSubscribe(false)
            .refer();
  }

  @AfterAll
  void tearDownServers() {
    if (providerConfig != null) {
      providerConfig.unExport();
    }
    if (errorProviderConfig != null) {
      errorProviderConfig.unExport();
    }
  }

  @Test
  void clientAndServerSpansForBoltCall() throws InterruptedException, TimeoutException {
    String serviceUniqueName = GreeterService.class.getName() + ":1.0";

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

    assertNotNull(clientSofaSpan, "Expected sofarpc client span");
    assertEquals(serviceUniqueName + "/sayHello", clientSofaSpan.getResourceName().toString());
    assertEquals("bolt", String.valueOf(clientSofaSpan.getTag("sofarpc.protocol")));
    assertEquals("sofarpc-client", String.valueOf(clientSofaSpan.getTag("component")));
    assertEquals("client", String.valueOf(clientSofaSpan.getTag("span.kind")));
    assertEquals("sofarpc", String.valueOf(clientSofaSpan.getTag("rpc.system")));
    assertEquals(serviceUniqueName, String.valueOf(clientSofaSpan.getTag("rpc.service")));
    assertEquals("sayHello", String.valueOf(clientSofaSpan.getTag("rpc.method")));
    assertEquals(callerSpan.getSpanId(), clientSofaSpan.getParentId());
    assertFalse(clientSofaSpan.isError());

    assertNotNull(serverSofaSpan, "Expected sofarpc server span");
    assertEquals(serviceUniqueName + "/sayHello", serverSofaSpan.getResourceName().toString());
    assertEquals("bolt", String.valueOf(serverSofaSpan.getTag("sofarpc.protocol")));
    assertEquals("sofarpc-server", String.valueOf(serverSofaSpan.getTag("component")));
    assertEquals("server", String.valueOf(serverSofaSpan.getTag("span.kind")));
    assertEquals("sofarpc", String.valueOf(serverSofaSpan.getTag("rpc.system")));
    assertEquals(serviceUniqueName, String.valueOf(serverSofaSpan.getTag("rpc.service")));
    assertEquals("sayHello", String.valueOf(serverSofaSpan.getTag("rpc.method")));
    assertEquals(clientSofaSpan.getSpanId(), serverSofaSpan.getParentId());
    assertFalse(serverSofaSpan.isError());
  }

  @Test
  void serverErrorIsMarkedOnServerSpan() throws InterruptedException, TimeoutException {
    // SOFA RPC Bolt propagates server exceptions back to the client as a SofaRpcException.
    // The client-side AbstractCluster.invoke() returns the SofaResponse to the proxy layer,
    // which then throws — after our instrumentation's OnMethodExit has already closed the scope.
    // So the CLIENT span is not errored; only the SERVER span reflects the error.
    String serviceUniqueName = FaultyService.class.getName() + ":1.0";

    assertThrows(Exception.class, () -> faultyService.fail());

    writer.waitForTraces(2);
    List<DDSpan> allSpans = flattenTraces();

    DDSpan clientSofaSpan = findSpan(allSpans, "sofarpc.request", "client");
    DDSpan serverSofaSpan = findSpan(allSpans, "sofarpc.request", "server");

    assertNotNull(clientSofaSpan, "Expected sofarpc client span");
    assertEquals(serviceUniqueName + "/fail", clientSofaSpan.getResourceName().toString());
    assertFalse(clientSofaSpan.isError(), "Client span should not be errored");

    assertNotNull(serverSofaSpan, "Expected sofarpc server span");
    assertEquals(serviceUniqueName + "/fail", serverSofaSpan.getResourceName().toString());
    assertTrue(serverSofaSpan.isError(), "Server span should be errored");
    assertNotNull(
        serverSofaSpan.getTag("error.message"), "Expected error.message tag on server span");
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

  public interface GreeterService {
    String sayHello(String name);
  }

  static class GreeterServiceImpl implements GreeterService {
    @Override
    public String sayHello(String name) {
      return "Hello, " + name;
    }
  }

  public interface FaultyService {
    // Non-void return type: SOFA RPC Bolt throws SofaRpcException on client side
    // when the server returns an error response, which is what we verify in the test.
    String fail();
  }

  static class FaultyServiceImpl implements FaultyService {
    @Override
    public String fail() {
      throw new IllegalStateException("something went wrong");
    }
  }
}
