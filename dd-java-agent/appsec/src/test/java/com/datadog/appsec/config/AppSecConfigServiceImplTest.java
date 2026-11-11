package com.datadog.appsec.config;

import static datadog.remoteconfig.PollingHinterNoop.NOOP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.appsec.AppSecSystem;
import com.datadog.ddwaf.exception.InvalidRuleSetException;
import com.datadog.ddwaf.exception.UnclassifiedWafException;
import datadog.remoteconfig.ConfigurationEndListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.UserIdCollectionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests how {@link AppSecConfigServiceImpl} handles rejected and removed ASM_DD configs against the
 * real WAF.
 */
class AppSecConfigServiceImplTest {

  private static final String DEFAULT_CONFIG_KEY = "ASM_DD/default";
  private static final ConfigKey KEY = ParsedConfigKey.parse("datadog/2/ASM_DD/test-config/config");
  private static final ConfigKey OTHER_KEY =
      ParsedConfigKey.parse("datadog/2/ASM_DD/other-config/config");

  private static final String VALID_CONFIG =
      "{\"version\": \"2.2\","
          + " \"metadata\": {\"rules_version\": \"1.2.3\"},"
          + " \"rules\": [{"
          + "\"id\": \"test-rule\","
          + " \"name\": \"Test rule\","
          + " \"tags\": {\"type\": \"attack_attempt\", \"category\": \"attack_attempt\"},"
          + " \"conditions\": [{"
          + "\"operator\": \"match_regex\","
          + " \"parameters\": {"
          + "\"inputs\": [{\"address\": \"server.request.query\"}],"
          + " \"regex\": \"^attack$\"}}]}]}";

  private static final String INVALID_VERSION_CONFIG =
      VALID_CONFIG.replace("\"version\": \"2.2\"", "\"version\": \"abc\"");

  private static final String OTHER_VALID_CONFIG =
      VALID_CONFIG.replace("\"id\": \"test-rule\"", "\"id\": \"other-rule\"");

  private final ConfigurationPoller poller = mock(ConfigurationPoller.class);
  private final Config config = mock(Config.class);
  private final AppSecModuleConfigurer.Reconfiguration reconfiguration =
      mock(AppSecModuleConfigurer.Reconfiguration.class);

  private boolean originalActive;
  private AppSecConfigServiceImpl service;
  private ProductListener asmDdListener;
  private ConfigurationEndListener configurationEndListener;

  @BeforeEach
  void setUp() {
    originalActive = AppSecSystem.isActive();
    AppSecSystem.setActive(true);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.FULLY_ENABLED);
    // keep the global user id collection mode untouched by the configuration-end listener
    when(config.getAppSecUserIdCollectionMode()).thenReturn(UserIdCollectionMode.get());

    service = new AppSecConfigServiceImpl(config, poller, reconfiguration);
    service.init();
    service.maybeSubscribeConfigPolling();

    ArgumentCaptor<ProductListener> listenerCaptor = ArgumentCaptor.forClass(ProductListener.class);
    verify(poller).addListener(eq(Product.ASM_DD), listenerCaptor.capture());
    asmDdListener = listenerCaptor.getValue();

    ArgumentCaptor<ConfigurationEndListener> endListenerCaptor =
        ArgumentCaptor.forClass(ConfigurationEndListener.class);
    verify(poller).addConfigurationEndListener(endListenerCaptor.capture());
    configurationEndListener = endListenerCaptor.getValue();
  }

  @AfterEach
  void tearDown() {
    service.close();
    AppSecSystem.setActive(originalActive);
  }

  @Test
  void defaultRulesAreRestoredAfterRemovingARejectedUpdate() throws Exception {
    asmDdListener.accept(KEY, VALID_CONFIG.getBytes(UTF_8), NOOP);
    acceptRejectedUpdate(KEY);

    assertDoesNotThrow(() -> asmDdListener.remove(KEY, NOOP));
    configurationEndListener.onConfigurationEnd();

    // only succeeds if the default config was registered again
    assertDoesNotThrow(() -> service.getWafBuilder().removeConfig(DEFAULT_CONFIG_KEY));
  }

  @Test
  void defaultRulesAreRestoredWhileARejectedUpdateIsStillPublished() throws Exception {
    asmDdListener.accept(KEY, VALID_CONFIG.getBytes(UTF_8), NOOP);
    acceptRejectedUpdate(KEY);

    configurationEndListener.onConfigurationEnd();

    // only succeeds if the default config was registered again
    assertDoesNotThrow(() -> service.getWafBuilder().removeConfig(DEFAULT_CONFIG_KEY));
  }

  @Test
  void defaultRulesStayRemovedWhileAnotherConfigIsApplied() throws Exception {
    asmDdListener.accept(OTHER_KEY, OTHER_VALID_CONFIG.getBytes(UTF_8), NOOP);
    asmDdListener.accept(KEY, VALID_CONFIG.getBytes(UTF_8), NOOP);
    acceptRejectedUpdate(KEY);

    configurationEndListener.onConfigurationEnd();

    // the default config is not registered: OTHER_KEY still provides the rules
    assertThrows(
        UnclassifiedWafException.class,
        () -> service.getWafBuilder().removeConfig(DEFAULT_CONFIG_KEY));
  }

  @Test
  void removingAKeyThatWasNeverAppliedDoesNotThrow() {
    assertDoesNotThrow(() -> asmDdListener.remove(KEY, NOOP));
  }

  /** The WAF rejects the update and, since libddwaf 2.2.0, unregisters the path. */
  private void acceptRejectedUpdate(ConfigKey key) {
    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () -> asmDdListener.accept(key, INVALID_VERSION_CONFIG.getBytes(UTF_8), NOOP));
    assertInstanceOf(InvalidRuleSetException.class, e.getCause());
  }
}
