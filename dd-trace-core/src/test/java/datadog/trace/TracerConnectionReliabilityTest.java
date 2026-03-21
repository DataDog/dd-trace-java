package datadog.trace;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.metrics.api.Monitoring;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.core.CoreTracer;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Properties;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class TracerConnectionReliabilityTest {

  static final int FEATURES_DISCOVERY_MIN_DELAY = 10;

  static OkHttpClient client;
  static JsonAdapter<List<List<SentTraces>>> traceJsonAdapter;

  int agentContainerPort;
  CoreTracer tracer;

  @BeforeAll
  static void setupSpec() {
    client = new OkHttpClient();
    Moshi moshi = new Moshi.Builder().build();
    Type type =
        Types.newParameterizedType(
            List.class, Types.newParameterizedType(List.class, SentTraces.class));
    traceJsonAdapter = moshi.adapter(type);
  }

  @BeforeEach
  void setup() throws Exception {
    agentContainerPort = PortUtils.randomOpenPort();
    Properties properties = new Properties();
    properties.put("trace.agent.port", Integer.toString(agentContainerPort));
    SharedCommunicationObjects sharedCommunicationObjects = new SharedCommunicationObjects();
    sharedCommunicationObjects.agentUrl = HttpUrl.get("http://localhost:" + agentContainerPort);
    sharedCommunicationObjects.agentHttpClient = client;
    FixedTraceEndpointFeaturesDiscovery fixedFeaturesDiscovery =
        new FixedTraceEndpointFeaturesDiscovery(sharedCommunicationObjects);
    sharedCommunicationObjects.setFeaturesDiscovery(fixedFeaturesDiscovery);

    tracer =
        CoreTracer.builder()
            .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
            .withProperties(properties)
            .sharedCommunicationObjects(sharedCommunicationObjects)
            .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void testLateAgentStart() throws Exception {
    createSpans(10, 100);
    tracer.flush();

    GenericContainer agentContainer = startTestAgentContainer();
    int noAgentCount = getTraceCount(agentContainer);
    waitForDiscoveryTimeout();

    createSpans(20, 100);
    tracer.flush();
    int withAgentCount = getTraceCount(agentContainer);
    agentContainer.stop();

    assertFalse(agentContainer.isRunning());
    assertEquals(0, noAgentCount);
    assertEquals(20, withAgentCount);
  }

  @Test
  void testAgentRestart() throws Exception {
    GenericContainer agentContainer = startTestAgentContainer();

    createSpans(10, 100);
    tracer.flush();
    int withAgentCount = getTraceCount(agentContainer);

    assertEquals(10, withAgentCount);

    agentContainer.stop();
    createSpans(10, 100);
    tracer.flush();

    waitForDiscoveryTimeout();
    agentContainer = startTestAgentContainer();
    int noTraceCount = getTraceCount(agentContainer);
    createSpans(10, 100);
    tracer.flush();
    withAgentCount = getTraceCount(agentContainer);
    agentContainer.stop();

    assertFalse(agentContainer.isRunning());
    assertEquals(0, noTraceCount);
    assertEquals(10, withAgentCount);
  }

  @SuppressWarnings("deprecation")
  GenericContainer startTestAgentContainer() {
    // Use FixedHostPortGenericContainer against deprecation because we need to know the exposed
    // port to configure the tracer at start
    @SuppressWarnings("resource")
    FixedHostPortGenericContainer<?> agentContainer =
        new FixedHostPortGenericContainer<>(
                "ghcr.io/datadog/dd-apm-test-agent/ddapm-test-agent:v1.27.1")
            .withFixedExposedPort(agentContainerPort, DEFAULT_TRACE_AGENT_PORT)
            .withEnv(
                "ENABLED_CHECKS",
                "trace_count_header,meta_tracer_version_header,trace_content_length")
            .waitingFor(Wait.forHttp("/test/traces"));
    agentContainer.start();
    return agentContainer;
  }

  void createSpans(int count, int delay) throws InterruptedException {
    for (int index = 1; index <= count; index++) {
      datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
          tracer.buildSpan("operation-" + index).start();
      Thread.sleep(delay);
      span.finish();
    }
  }

  static void waitForDiscoveryTimeout() throws InterruptedException {
    Thread.sleep((long) (FEATURES_DISCOVERY_MIN_DELAY * 1.5));
  }

  int getTraceCount(GenericContainer agentContainer) throws Exception {
    Request request =
        new Request.Builder()
            .url("http://" + agentContainer.getHost() + ":" + agentContainerPort + "/test/traces")
            .build();
    okhttp3.Response execute = client.newCall(request).execute();
    String body = execute.body().string();
    return traceJsonAdapter.fromJson(body).size();
  }

  class FixedTraceEndpointFeaturesDiscovery extends DDAgentFeaturesDiscovery {
    FixedTraceEndpointFeaturesDiscovery(SharedCommunicationObjects objects) {
      super(objects.agentHttpClient, Monitoring.DISABLED, objects.agentUrl, false, false);
    }

    @Override
    public String getTraceEndpoint() {
      return V04_ENDPOINT;
    }

    @Override
    protected long getFeaturesDiscoveryMinDelayMillis() {
      return FEATURES_DISCOVERY_MIN_DELAY;
    }
  }

  static class SentTraces {
    String name;
  }
}
