package com.datadog.appsec;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.metrics.api.Monitoring;
import datadog.remoteconfig.ConfigurationEndListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.Subscription;
import datadog.trace.api.gateway.SubscriptionService;
import java.nio.charset.StandardCharsets;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the fix end to end through {@link AppSecSystem}, not just at the {@link
 * com.datadog.appsec.gateway.GatewayBridge} unit level: a Remote Config update must reach {@link
 * AppSecSystem#reloadSubscriptions} and re-evaluate the conditionally registered instrumentation
 * gateway callbacks without throwing.
 *
 * <p>The default ruleset already requires every conditional address at startup, so this test cannot
 * exercise the "not required at startup, required after reload" transition (that is covered at the
 * {@link com.datadog.appsec.gateway.GatewayBridge} level, where the subscribed addresses are fully
 * controlled). What it does verify is that the real wiring between {@code AppSecConfigServiceImpl}
 * / {@code WAFModule} and {@code GatewayBridge} is idempotent: a second, RC-triggered pass over an
 * already-registered callback must not throw {@link IllegalStateException}.
 */
class AppSecSystemReloadRegistrationTest {

  private static final String RULE_REQUIRING_PATH_PARAMS =
      "{"
          + "\"version\": \"2.1\","
          + "\"rules\": [{"
          + "  \"id\": \"path-params-rule\","
          + "  \"name\": \"path-params-rule\","
          + "  \"conditions\": [{"
          + "    \"operator\": \"match_regex\","
          + "    \"parameters\": {"
          + "      \"inputs\": [{\"address\": \"server.request.path_params\"}],"
          + "      \"regex\": \"foo\""
          + "    }"
          + "  }],"
          + "  \"tags\": {\"type\": \"t\", \"category\": \"c\"},"
          + "  \"action\": \"record\""
          + "}]"
          + "}";

  private final SubscriptionService subService = mock(SubscriptionService.class);
  private final ConfigurationPoller poller = mock(ConfigurationPoller.class);

  @BeforeEach
  void setUp() {
    // a bare mock returns null from registerCallback, which would defeat the
    // volatile-Subscription idempotency guard in GatewayBridge on every call
    when(subService.registerCallback(any(), any())).thenReturn(mock(Subscription.class));
  }

  @AfterEach
  void tearDown() {
    AppSecSystem.stop();
  }

  @Test
  void reloadThroughRemoteConfigDoesNotReRegisterCallback() throws Exception {
    AppSecSystem.start(subService, sharedCommunicationObjects());

    ArgumentCaptor<ProductListener> asmListenerCaptor =
        ArgumentCaptor.forClass(ProductListener.class);
    verify(poller).addListener(eq(Product.ASM_DD), asmListenerCaptor.capture());

    ArgumentCaptor<ConfigurationEndListener> confEndListenerCaptor =
        ArgumentCaptor.forClass(ConfigurationEndListener.class);
    verify(poller).addConfigurationEndListener(confEndListenerCaptor.capture());

    // the default ruleset already requires server.request.path_params, so it is registered
    // at startup
    verify(subService, times(1)).registerCallback(eq(EVENTS.requestPathParams()), any());

    // Remote Config delivers a ruleset that also requires server.request.path_params
    assertDoesNotThrow(
        () -> {
          asmListenerCaptor
              .getValue()
              .accept(
                  mock(ConfigKey.class),
                  RULE_REQUIRING_PATH_PARAMS.getBytes(StandardCharsets.UTF_8),
                  null);
          confEndListenerCaptor.getValue().onConfigurationEnd();
        });

    // the already-registered callback is left untouched, not re-registered
    verify(subService, times(1)).registerCallback(eq(EVENTS.requestPathParams()), any());
  }

  private SharedCommunicationObjects sharedCommunicationObjects() {
    SharedCommunicationObjects sco =
        new SharedCommunicationObjects() {
          @Override
          public ConfigurationPoller configurationPoller(Config config) {
            return poller;
          }
        };
    sco.agentHttpClient = mock(OkHttpClient.class);
    sco.monitoring = mock(Monitoring.class);
    sco.setFeaturesDiscovery(mock(DDAgentFeaturesDiscovery.class));
    return sco;
  }
}
