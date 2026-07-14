package datadog.trace.common.metrics;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V06_METRICS_ENDPOINT;
import static datadog.trace.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.http.OkHttpUtils;
import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.junit.utils.config.WithConfigExtension;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy;

@ExtendWith(WithConfigExtension.class)
class MetricsIntegrationTest {

  // CI runs an agent container alongside the build (reached via CI_AGENT_HOST); when building
  // locally we start one ourselves with testcontainers.
  private static final boolean RUNNING_IN_CI = "true".equals(System.getenv("CI"));
  private static GenericContainer<?> agentContainer;

  @BeforeAll
  static void setupSpec() {
    // recordOneDuration -> Histogram.accept needs the metrics meter / histogram factory registered.
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
    AgentMeter.registerIfAbsent(StatsDClient.NO_OP, monitoring, DDSketchHistograms.FACTORY);

    if (!RUNNING_IN_CI) {
      Map<String, String> env = new HashMap<>();
      env.put("DD_APM_ENABLED", "true");
      env.put("DD_BIND_HOST", "0.0.0.0");
      env.put("DD_API_KEY", "invalid_key_but_this_is_fine");
      env.put("DD_HOSTNAME", "doesnotexist");
      env.put("DD_LOGS_STDOUT", "yes");
      agentContainer =
          new GenericContainer<>("datadog/agent:7.40.1")
              .withEnv(env)
              .withExposedPorts(ConfigDefaults.DEFAULT_TRACE_AGENT_PORT)
              .withStartupTimeout(Duration.ofSeconds(120))
              // Sleep for a bit so the agent's rate_by_service response is populated -- mirrors the
              // race-condition workaround from the original Spock base.
              .withStartupCheckStrategy(
                  new MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(10)));
      agentContainer.start();
    }
  }

  @AfterAll
  static void cleanupSpec() {
    if (agentContainer != null) {
      agentContainer.stop();
    }
  }

  @BeforeEach
  void setup() {
    injectSysConfig(TracerConfig.AGENT_HOST, agentHost());
    injectSysConfig(TracerConfig.TRACE_AGENT_PORT, agentPort());
  }

  private static String agentHost() {
    return agentContainer != null ? agentContainer.getHost() : System.getenv("CI_AGENT_HOST");
  }

  private static String agentPort() {
    return agentContainer != null
        ? String.valueOf(agentContainer.getMappedPort(ConfigDefaults.DEFAULT_TRACE_AGENT_PORT))
        : String.valueOf(ConfigDefaults.DEFAULT_TRACE_AGENT_PORT);
  }

  @Test
  void sendMetricsToTraceAgentShouldNotifyWithOkEvent() throws InterruptedException {
    // setup
    CountDownLatch latch = new CountDownLatch(1);
    BlockingListener listener = new BlockingListener(latch);
    String agentUrl = Config.get().getAgentUrl();
    OkHttpSink sink =
        new OkHttpSink(
            OkHttpUtils.buildHttpClient(HttpUrl.parse(agentUrl), 5000L),
            agentUrl,
            V06_METRICS_ENDPOINT,
            true,
            false,
            Collections.emptyMap());
    sink.register(listener);

    // when
    SerializingMetricWriter writer =
        new SerializingMetricWriter(
            new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language"),
            sink);
    writer.startBucket(2, System.nanoTime(), SECONDS.toNanos(10));
    // Build entries through the production AggregateTable.findOrInsert path (canonicalizes the
    // snapshot and creates/looks up the entry). Both entries use one peer tag (grault:quux) and no
    // additional tags -> schema names=["grault"], values=["quux"].
    AggregateTable table = new AggregateTable(8);
    PeerTagSchema schema = new PeerTagSchema(new String[] {"grault"}, PeerTagSchema.NO_STATE);
    SpanSnapshot snap1 =
        new SpanSnapshot(
            "resource1",
            "service1",
            "operation1",
            null,
            "sql",
            (short) 0,
            false,
            true,
            "xyzzy",
            schema,
            new String[] {"quux"},
            null,
            null,
            null,
            null,
            0L);
    AggregateEntry entry1 = table.findOrInsert(snap1);
    for (long duration : new long[] {2, 1, 2, 250, 4}) {
      entry1.recordOneDuration(duration);
    }
    writer.add(entry1);
    SpanSnapshot snap2 =
        new SpanSnapshot(
            "resource2",
            "service2",
            "operation2",
            null,
            "web",
            (short) 200,
            false,
            true,
            "xyzzy",
            schema,
            new String[] {"quux"},
            null,
            null,
            null,
            null,
            0L);
    AggregateEntry entry2 = table.findOrInsert(snap2);
    for (long duration : new long[] {1, 1, 200, 2, 3, 4, 5, 6, 7, 8}) {
      entry2.recordOneDuration(duration);
    }
    writer.add(entry2);
    writer.finishBucket();

    // then
    assertTrue(latch.await(5, SECONDS));
    assertEquals(1, listener.events.size());
    assertEquals(EventListener.EventType.OK, listener.events.get(0));
  }

  static class BlockingListener implements EventListener {
    final List<EventListener.EventType> events = new CopyOnWriteArrayList<>();
    final CountDownLatch latch;

    BlockingListener(CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public void onEvent(EventListener.EventType eventType, String message) {
      events.add(eventType);
      latch.countDown();
    }
  }
}
