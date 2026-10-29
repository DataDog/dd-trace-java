package com.datadog.openfeature;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.Provider.Options;
import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.ConfigurationSource;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import com.datadog.openfeature.internal.bridge.v1.ShutdownSource;
import com.datadog.openfeature.internal.bridge.v1.SpanEnricher;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Turns Datadog exposure logging off through the real setting, read by the runtime. */
class DatadogExposureLoggingTest {

  private final List<ExposureEvent> sentToDatadog = new CopyOnWriteArrayList<>();
  private final List<ExposureHook.Evaluation> evaluations = new ArrayList<>();
  private LocalHttpServer cdn;

  @BeforeEach
  void setUp() throws IOException {
    this.cdn = new LocalHttpServer();
  }

  @AfterEach
  void tearDown() {
    OpenFeatureAPI.getInstance().clearHooks();
    OpenFeatureAPI.getInstance().shutdown();
    this.cdn.close();
  }

  @Test
  void sendsNoExposuresToDatadogWhileExposureHooksKeepTheirDecisions() throws Exception {
    final Client client = start("false");

    client.getIntegerDetails("flag", 0, new MutableContext("user-1"));
    client.getIntegerDetails("flag", 0, new MutableContext("user-1"));

    assertTrue(this.sentToDatadog.isEmpty(), "nothing is sent to Datadog");
    assertEquals(2, this.evaluations.size());
    assertTrue(this.evaluations.get(0).shouldSend());
    assertFalse(this.evaluations.get(1).shouldSend(), "the cache still records the first exposure");
  }

  @Test
  void sendsExposuresToDatadogByDefault() throws Exception {
    final Client client = start(null);

    client.getIntegerDetails("flag", 0, new MutableContext("user-1"));
    client.getIntegerDetails("flag", 0, new MutableContext("user-1"));

    assertEquals(1, this.sentToDatadog.size());
    assertEquals("user-1", this.sentToDatadog.get(0).subject.id);
  }

  private Client start(final String exposuresDatadogLoggingEnabled) throws Exception {
    final List<String> settings = new ArrayList<>();
    settings.add("feature.flags.configuration.source.agentless.base.url");
    settings.add(this.cdn.uri("/").toString());
    settings.add("flagging.evaluation.counts.enabled");
    settings.add("false");
    if (exposuresDatadogLoggingEnabled != null) {
      settings.add("feature.flags.exposures.datadog.logging.enabled");
      settings.add(exposuresDatadogLoggingEnabled);
    }
    final DDEvaluator evaluator =
        new DDEvaluator(
            () -> {},
            new ProxyAgentBridge((route, json) -> {}),
            TestSettings.of(settings.toArray(new String[0]))) {
          @Override
          void recordExposure(final ExposureEvent event) {
            DatadogExposureLoggingTest.this.sentToDatadog.add(event);
          }
        };
    evaluator.accept(configuration());
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(new Provider(new Options().initTimeout(10, SECONDS), evaluator));
    api.addHooks(new ExposureHook(this.evaluations::add));
    return api.getClient();
  }

  private static ServerConfiguration configuration() {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    final Split split = new Split(emptyList(), "on", emptyMap(), 7);
    final Allocation allocation =
        new Allocation("allocation", null, null, null, singletonList(split), Boolean.TRUE);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "flag", new Flag("flag", true, ValueType.INTEGER, variations, singletonList(allocation)));
    return new ServerConfiguration("", "", false, null, flags);
  }

  /** A bridge only providing an event platform proxy. */
  private static final class ProxyAgentBridge implements AgentBridge {
    private final EventTransport proxy;

    ProxyAgentBridge(final EventTransport proxy) {
      this.proxy = proxy;
    }

    @Override
    public String setting(final String key) {
      return null;
    }

    @Override
    public ConfigurationSource remoteConfiguration() {
      return null;
    }

    @Override
    public EventTransport eventProxy() {
      return this.proxy;
    }

    @Override
    public HealthMetrics healthMetrics() {
      return HealthMetrics.NOOP;
    }

    @Override
    public SpanEnricher spanEnricher() {
      return SpanEnricher.NOOP;
    }

    @Override
    public ShutdownSource shutdown() {
      return null;
    }
  }
}
