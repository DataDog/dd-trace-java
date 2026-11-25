package com.datadog.appsec.config;

import static com.datadog.appsec.TestResources.readResource;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_ACTIVATION;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_CUSTOM_DATA_SCANNERS;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_CUSTOM_RULES;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_DD_MULTICONFIG;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_DD_RULES;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_EXCLUSIONS;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_EXCLUSION_DATA;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_EXTENDED_DATA_COLLECTION;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_HEADER_FINGERPRINT;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_IP_BLOCKING;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_NETWORK_FINGERPRINT;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_PROCESSOR_OVERRIDES;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_CMDI;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_LFI;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_SHI;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_SQLI;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_RASP_SSRF;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_REQUEST_BLOCKING;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_SESSION_FINGERPRINT;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_TRACE_TAGGING_RULES;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_TRUSTED_IPS;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_USER_BLOCKING;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ENDPOINT_FINGERPRINT;
import static datadog.remoteconfig.PollingHinterNoop.NOOP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datadog.appsec.AppSecModule;
import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.util.AbortStartupException;
import com.datadog.ddwaf.exception.InvalidRuleSetException;
import com.datadog.ddwaf.exception.UnclassifiedWafException;
import datadog.remoteconfig.ConfigurationChangesListener;
import datadog.remoteconfig.ConfigurationChangesTypedListener;
import datadog.remoteconfig.ConfigurationDeserializer;
import datadog.remoteconfig.ConfigurationEndListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.UserIdCollectionMode;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tabletest.junit.TableTest;

class AppSecConfigServiceImplTest extends DDJavaSpecification {

  private static final long RULES_AND_DATA_CAPABILITIES =
      CAPABILITY_ASM_DD_RULES
          | CAPABILITY_ASM_DD_MULTICONFIG
          | CAPABILITY_ASM_IP_BLOCKING
          | CAPABILITY_ASM_EXCLUSIONS
          | CAPABILITY_ASM_EXCLUSION_DATA
          | CAPABILITY_ASM_REQUEST_BLOCKING
          | CAPABILITY_ASM_USER_BLOCKING
          | CAPABILITY_ASM_CUSTOM_RULES
          | CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE
          | CAPABILITY_ASM_TRUSTED_IPS
          | CAPABILITY_ASM_PROCESSOR_OVERRIDES
          | CAPABILITY_ASM_CUSTOM_DATA_SCANNERS
          | CAPABILITY_ENDPOINT_FINGERPRINT
          | CAPABILITY_ASM_SESSION_FINGERPRINT
          | CAPABILITY_ASM_NETWORK_FINGERPRINT
          | CAPABILITY_ASM_HEADER_FINGERPRINT
          | CAPABILITY_ASM_TRACE_TAGGING_RULES
          | CAPABILITY_ASM_EXTENDED_DATA_COLLECTION;
  private static final long RASP_CAPABILITIES_WITHOUT_LFI =
      CAPABILITY_ASM_RASP_SQLI
          | CAPABILITY_ASM_RASP_SSRF
          | CAPABILITY_ASM_RASP_CMDI
          | CAPABILITY_ASM_RASP_SHI;
  private static final long RASP_CAPABILITIES =
      RASP_CAPABILITIES_WITHOUT_LFI | CAPABILITY_ASM_RASP_LFI;
  private static final String ASM_ENABLED = "{\"asm\":{\"enabled\": true}}";
  private static final String ASM_DISABLED = "{\"asm\":{\"enabled\": false}}";
  private static final String ASM_FEATURES_ACTIVATION = "asm_features_activation";
  private static final String WAF_RULES_CONFIG = "config_service/waf_rules_config.json";
  private static final String RULES_OVERRIDE_CONFIG =
      "{\"rules_override\": [{\"rules_target\": [{\"rule_id\": \"foo\"}], \"enabled\": false}]}";
  private static final WafMetricCollector ORIGINAL_METRIC_COLLECTOR = WafMetricCollector.get();

  private final ConfigurationPoller poller = mock(ConfigurationPoller.class);
  private final Config config = mock(Config.class);
  private final AppSecModuleConfigurer.Reconfiguration reconf =
      mock(AppSecModuleConfigurer.Reconfiguration.class);
  private final AppSecModuleConfigurer.SubconfigListener subconfigListener =
      mock(AppSecModuleConfigurer.SubconfigListener.class);

  private boolean originalActive;
  private AppSecConfigServiceImpl appSecConfigService;

  @BeforeEach
  void setup() {
    originalActive = AppSecSystem.isActive();
    appSecConfigService = new AppSecConfigServiceImpl(config, poller, reconf);
    // only count the interactions of the test itself, not the ones of the constructor
    clearInvocations(config, poller);
  }

  @AfterEach
  void cleanup() {
    appSecConfigService.close();
    AppSecSystem.setActive(originalActive);
  }

  @Test
  void maybeStartConfigPollingSubscribesToTheConfigurationPoller() {
    // setup
    appSecConfigService.init();
    AppSecSystem.setActive(false);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.ENABLED_INACTIVE);

    // when
    appSecConfigService.maybeSubscribeConfigPolling();

    // then
    verify(poller, never()).addListener(eq(Product.ASM_DD), any(ProductListener.class));
    verify(poller)
        .addListener(
            eq(Product.ASM_FEATURES),
            any(ConfigurationDeserializer.class),
            any(ConfigurationChangesTypedListener.class));
    verify(poller, never()).addListener(eq(Product.ASM), any(ProductListener.class));
    verify(poller, never()).addListener(eq(Product.ASM_DATA), any(ProductListener.class));
    verify(poller).addConfigurationEndListener(any());
    verify(poller).addCapabilities(CAPABILITY_ASM_ACTIVATION);
  }

  @Test
  void noSubscriptionToAsmFeaturesIfAppsecIsFullyEnabled() {
    // setup
    appSecConfigService.init();
    AppSecSystem.setActive(true);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.FULLY_ENABLED);

    // when
    appSecConfigService.maybeSubscribeConfigPolling();

    // then
    verify(poller).addListener(eq(Product.ASM_DD), any(ProductListener.class));
    verify(poller)
        .addListener(
            eq(Product.ASM_FEATURES),
            any(ConfigurationDeserializer.class),
            any(ConfigurationChangesTypedListener.class));
    verify(poller).addListener(eq(Product.ASM), any(ProductListener.class));
    verify(poller).addListener(eq(Product.ASM_DATA), any(ProductListener.class));
    verify(poller).addConfigurationEndListener(any());
    verifyAddListenerCalls(3, 1);
    verify(poller, never()).addCapabilities(CAPABILITY_ASM_ACTIVATION);
  }

  @Test
  void noSubscriptionToAsmFeaturesIfAppsecIsFullyDisabled() {
    // setup
    appSecConfigService.init();
    AppSecSystem.setActive(false);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.FULLY_DISABLED);

    // when
    appSecConfigService.maybeSubscribeConfigPolling();

    // then
    verify(poller, never()).addConfigurationEndListener(any());
    verifyAddListenerCalls(0, 0);
    verify(poller, never()).addCapabilities(anyLong());
  }

  @Test
  void noSubscriptionToAsmAsmDdAsmDataIfCustomRulesAreProvided() throws Exception {
    // setup
    Path path = resourcePath("test_multi_config_no_action.json");
    AppSecSystem.setActive(false);
    when(config.getAppSecRulesFile()).thenReturn(path.toString());

    // when
    appSecConfigService.init();

    // then
    verify(config).getAppSecRulesFile();
    clearInvocations(config, poller);

    // when
    when(config.getAppSecActivation()).thenReturn(ProductActivation.ENABLED_INACTIVE);
    appSecConfigService.maybeSubscribeConfigPolling();

    // then
    verify(config, times(2)).getAppSecActivation();
    verify(poller)
        .addListener(
            eq(Product.ASM_FEATURES),
            any(ConfigurationDeserializer.class),
            any(ConfigurationChangesTypedListener.class));
    verify(poller).addConfigurationEndListener(any());
    verifyAddListenerCalls(0, 1);
  }

  @Test
  void canLoadFromADifferentLocation() throws Exception {
    // setup
    Path path = resourcePath("test_multi_config_no_action.json");
    when(config.getAppSecRulesFile()).thenReturn(path.toString());

    // when
    assertDoesNotThrow(() -> appSecConfigService.init());

    // then
    verify(config).getAppSecRulesFile();
  }

  @Test
  void abortsIfAltConfigLocationDoesNotExist() {
    // setup
    when(config.getAppSecRulesFile()).thenReturn("/file/that/does/not/exist");

    // when
    assertThrows(AbortStartupException.class, () -> appSecConfigService.init());

    // then
    verify(config).getAppSecRulesFile();
  }

  @Test
  void abortsIfAltConfigFileIsNotValidJson() throws IOException {
    // setup
    Path path = Files.createTempFile("appsec", ".json");
    Files.write(path, "THIS IS NOT JSON".getBytes(UTF_8));
    when(config.getAppSecRulesFile()).thenReturn(path.toString());

    // when
    assertThrows(AbortStartupException.class, () -> appSecConfigService.init());

    // then
    verify(config).getAppSecRulesFile();
  }

  @Test
  void providesInitialSubconfigurationUponSubscription() {
    // setup
    appSecConfigService.init();
    appSecConfigService.maybeSubscribeConfigPolling();

    // expect
    AppSecConfigService.TransactionalAppSecModuleConfigurer configurer =
        appSecConfigService.createAppSecModuleConfigurer();
    assertDoesNotThrow(() -> configurer.addSubConfigListener("waf", subconfigListener));
  }

  @Test
  void activationWithoutCustomConfigProvidesValidConfiguration() throws Exception {
    // when
    AppSecSystem.setActive(false);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.ENABLED_INACTIVE);
    appSecConfigService.init();
    appSecConfigService.maybeSubscribeConfigPolling();
    commitWafSubconfigListener();

    // then
    FeaturesListeners featuresListeners = verifyFeaturesListenerAdded();
    ConfigurationEndListener confEndListener = verifyConfigurationEndListenerAdded();
    verify(poller).addCapabilities(CAPABILITY_ASM_ACTIVATION);
    verify(poller).addCapabilities(CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE);
    verifyNoMoreInteractions(poller);
    clearInvocations(poller, subconfigListener);

    // when
    featuresListeners.accept("ignored config key", ASM_ENABLED);
    confEndListener.onConfigurationEnd();

    // then
    verify(subconfigListener).onNewSubconfig(any(), any());
    assertTrue(AppSecSystem.isActive());
    verify(poller).addListener(eq(Product.ASM_DD), any(ProductListener.class));
    verify(poller).addListener(eq(Product.ASM), any(ProductListener.class));
    verify(poller).addListener(eq(Product.ASM_DATA), any(ProductListener.class));
    verify(poller).addCapabilities(anyLong());
  }

  @Test
  void providesUpdatedConfigurationToWafSubscription() throws Exception {
    AppSecSystem.setActive(false);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.ENABLED_INACTIVE);
    when(config.isAppSecRaspEnabled()).thenReturn(true);
    appSecConfigService.init();
    clearInvocations(poller);

    // when
    appSecConfigService.maybeSubscribeConfigPolling();
    commitWafSubconfigListener();

    // then
    FeaturesListeners featuresListeners = verifyFeaturesListenerAdded();
    ConfigurationEndListener confEndListener = verifyConfigurationEndListenerAdded();
    verify(poller).addCapabilities(CAPABILITY_ASM_ACTIVATION);
    verify(poller).addCapabilities(CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE);
    verifyNoMoreInteractions(poller);
    clearInvocations(poller, subconfigListener);

    // when
    featuresListeners.accept(ASM_FEATURES_ACTIVATION, ASM_ENABLED);
    confEndListener.onConfigurationEnd();

    // then
    verify(subconfigListener).onNewSubconfig(anyString(), any());
    assertTrue(AppSecSystem.isActive());
    ProductListener wafDataChangesListener = verifyProductListenerAdded(Product.ASM_DD);
    verify(poller).addListener(eq(Product.ASM_DATA), any(ProductListener.class));
    verify(poller).addListener(eq(Product.ASM), any(ProductListener.class));
    verify(poller).addCapabilities(RULES_AND_DATA_CAPABILITIES | RASP_CAPABILITIES_WITHOUT_LFI);
    verifyNoMoreInteractions(poller);
    clearInvocations(poller, subconfigListener);

    // when
    featuresListeners.accept(ASM_FEATURES_ACTIVATION, ASM_ENABLED);
    confEndListener.onConfigurationEnd();

    // then
    verify(subconfigListener).onNewSubconfig(anyString(), any());
    assertTrue(AppSecSystem.isActive());
    clearInvocations(subconfigListener);

    // when
    // AppSec is ACTIVE - rules trigger subscriptions
    ConfigKey ignoredConfigKey = ParsedConfigKey.parse("datadog/2/ASM_DD/ignored/config");
    wafDataChangesListener.accept(
        ignoredConfigKey, readResource(WAF_RULES_CONFIG).getBytes(UTF_8), null);
    wafDataChangesListener.accept(ignoredConfigKey, RULES_OVERRIDE_CONFIG.getBytes(UTF_8), null);
    confEndListener.onConfigurationEnd();

    // then
    verify(subconfigListener).onNewSubconfig(any(), any());

    // when
    featuresListeners.accept(ASM_FEATURES_ACTIVATION, ASM_DISABLED);
    confEndListener.onConfigurationEnd();

    // then
    assertFalse(AppSecSystem.isActive());

    // when: switch back to enabled
    featuresListeners.accept(ASM_FEATURES_ACTIVATION, ASM_ENABLED);
    confEndListener.onConfigurationEnd();

    // then: it is enabled again
    assertTrue(AppSecSystem.isActive());

    // when: asm are not set
    featuresListeners.accept(ASM_FEATURES_ACTIVATION, null);
    confEndListener.onConfigurationEnd();

    // then: it is disabled (<not set> == false)
    assertFalse(AppSecSystem.isActive());

    // when: switch back to enabled
    featuresListeners.accept(ASM_FEATURES_ACTIVATION, ASM_ENABLED);
    confEndListener.onConfigurationEnd();

    // then: it is enabled again
    assertTrue(AppSecSystem.isActive());

    // when: asm features are not set
    featuresListeners.accept(ASM_FEATURES_ACTIVATION, null);
    confEndListener.onConfigurationEnd();

    // then: it is disabled (<not set> == false)
    assertFalse(AppSecSystem.isActive());
  }

  @Test
  void configurationPullOut() throws Exception {
    // when
    AppSecSystem.setActive(false);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.ENABLED_INACTIVE);
    appSecConfigService.init();
    appSecConfigService.maybeSubscribeConfigPolling();
    commitWafSubconfigListener();

    // then
    FeaturesListeners featuresListeners = verifyFeaturesListenerAdded();
    ConfigurationEndListener confEndListener = verifyConfigurationEndListenerAdded();
    verify(poller).addCapabilities(CAPABILITY_ASM_ACTIVATION);
    verify(poller).addCapabilities(CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE);
    verifyNoMoreInteractions(poller);
    clearInvocations(poller, subconfigListener);

    // when
    featuresListeners.accept("asm_features conf", ASM_ENABLED);
    confEndListener.onConfigurationEnd();

    // then
    assertTrue(AppSecSystem.isActive());
    ProductListener wafDataChangesListener = verifyProductListenerAdded(Product.ASM_DD);
    verify(poller).addListener(eq(Product.ASM_DATA), any(ProductListener.class));
    verify(poller).addListener(eq(Product.ASM), any(ProductListener.class));
    verify(poller).addCapabilities(RULES_AND_DATA_CAPABILITIES);
    verifyNoMoreInteractions(poller);
    clearInvocations(poller, subconfigListener);

    // when
    ConfigKey asmDdConfigKey = ParsedConfigKey.parse("datadog/1/ASM_DD/asm_dd_config/config");
    ConfigKey asmConfigKey = ParsedConfigKey.parse("datadog/1/ASM/asm_conf/config");
    wafDataChangesListener.accept(
        asmDdConfigKey, readResource(WAF_RULES_CONFIG).getBytes(UTF_8), null);
    wafDataChangesListener.accept(asmConfigKey, RULES_OVERRIDE_CONFIG.getBytes(UTF_8), null);
    confEndListener.onConfigurationEnd();

    // then
    verify(subconfigListener).onNewSubconfig(any(), any());

    // when / then
    assertDoesNotThrow(() -> {
      wafDataChangesListener.accept(asmDdConfigKey, null, null);
      wafDataChangesListener.accept(asmConfigKey, null, null);
      confEndListener.onConfigurationEnd();
    });
  }

  @Test
  void stoppingAppsecUnsubscribesFromThePoller() {
    // setup
    appSecConfigService.maybeSubscribeConfigPolling();

    // when
    appSecConfigService.close();

    // then
    verify(poller)
        .removeCapabilities(
            CAPABILITY_ASM_ACTIVATION
                | RULES_AND_DATA_CAPABILITIES
                | RASP_CAPABILITIES
                | CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE);
    verify(poller, times(4)).removeListeners(any());
    verify(poller).removeConfigurationEndListener(any());
    verify(poller).stop();
  }

  @TableTest({
    "scenario                | hasFeatures | mode           | expected      ",
    "no features             | false       |                | IDENTIFICATION",
    "no mode                 | true        |                | IDENTIFICATION",
    "identification mode     | true        | identification | IDENTIFICATION",
    "anonymization mode      | true        | anonymization  | ANONYMIZATION ",
    "disabled mode           | true        | disabled       | DISABLED      ",
    "unknown mode is ignored | true        | yolo           | DISABLED      "
  })
  void updateAutoUserInstrumModeViaRemoteConfig(
      boolean hasFeatures, String mode, UserIdCollectionMode expected) {
    // given
    AppSecFeatures features = hasFeatures ? autoUserInstrum(mode) : null;

    // when
    appSecConfigService.init();
    appSecConfigService.maybeSubscribeConfigPolling();

    // then
    FeaturesListeners featuresListeners = verifyFeaturesListenerAdded();
    verify(poller).addCapabilities(CAPABILITY_ASM_AUTO_USER_INSTRUM_MODE);
    ConfigurationEndListener confEndListener = verifyConfigurationEndListenerAdded();

    // when
    featuresListeners.listener.accept("asm_auto_user_instrum", features, null);
    confEndListener.onConfigurationEnd();

    // then
    assertEquals(expected, UserIdCollectionMode.get());
  }

  @Test
  void raspCapabilitiesAreNotSentWhenRaspIsNotFullyEnabled() {
    // when
    AppSecSystem.setActive(true);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.FULLY_ENABLED);
    when(config.isAppSecRaspEnabled()).thenReturn(false);
    appSecConfigService.init();
    appSecConfigService.maybeSubscribeConfigPolling();
    commitWafSubconfigListener();

    // then
    ArgumentCaptor<Long> capabilitiesCaptor = ArgumentCaptor.forClass(Long.class);
    verify(poller, atLeastOnce()).addCapabilities(capabilitiesCaptor.capture());
    for (long capabilities : capabilitiesCaptor.getAllValues()) {
      assertEquals(0L, capabilities & RASP_CAPABILITIES, "RASP capabilities must not be sent");
    }
  }

  @Test
  void testAppSecConfigChangesListenerListener() throws Exception {
    // setup: capture the listener registered for the ASM product
    AppSecSystem.setActive(true);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.FULLY_ENABLED);
    appSecConfigService.init();
    appSecConfigService.maybeSubscribeConfigPolling();
    ProductListener listener = verifyProductListenerAdded(Product.ASM);
    ConfigKey configKey = ParsedConfigKey.parse("datadog/2/ASM/my_config/config");

    // when / then: removing an unexisting config is tolerated
    assertDoesNotThrow(() -> listener.remove(configKey, null));

    // when: a config without any known WAF section is received
    byte[] waf = "{\"waf\": null}".getBytes(UTF_8);
    listener.accept(configKey, waf, null);

    // then: it is still registered in the WAF builder, so it can be removed
    assertDoesNotThrow(
        () -> appSecConfigService.getWafBuilder().removeConfig(configKey.toString()));
  }

  @Test
  void invalidRuleSetExceptionIsThrownWhenRulesAreNotConfiguredCorrectly() throws IOException {
    // setup
    // Mock WafMetricCollector
    WafMetricCollector wafMetricCollector = mock(WafMetricCollector.class);

    // Create a temporary file with invalid WAF configuration, before replacing the global collector
    Path path = Files.createTempFile("appsec", ".json");
    WafMetricCollector.INSTANCE = wafMetricCollector;
    try {
      String invalidConfig = readResource("config_service/invalid_waf_config.json");
      Files.write(path, invalidConfig.getBytes(UTF_8));
      when(config.getAppSecRulesFile()).thenReturn(path.toString());

      // when
      assertThrows(RuntimeException.class, () -> appSecConfigService.init());

      // then
      verify(config).getAppSecRulesFile();
      verify(wafMetricCollector).addWafConfigError(anyInt());
    } finally {
      // cleanup
      WafMetricCollector.INSTANCE = ORIGINAL_METRIC_COLLECTOR;
      Files.delete(path);
    }
  }

  // https://github.com/DataDog/dd-trace-java/issues/9159
  @Test
  void testInitializationIssuesWhileApplyingRemoteConfig() {
    // setup
    ConfigKey key = ParsedConfigKey.parse("datadog/1234/ASM_DD/ID/config");
    AppSecConfigServiceImpl service = new AppSecConfigServiceImpl(config, poller, reconf);
    // AppSec must be active for the ASM_DD subscription
    AppSecSystem.setActive(true);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.ENABLED_INACTIVE);

    // when
    service.maybeSubscribeConfigPolling();

    // then
    ProductListener wafDataChangesListener = verifyProductListenerAdded(Product.ASM_DD);

    // when / then
    assertDoesNotThrow(
        () -> wafDataChangesListener.accept(key, RULES_OVERRIDE_CONFIG.getBytes(UTF_8), NOOP));
  }

  @Test
  void currentRuleVersionIsUpdatedFromDiagnosticsWhenInvalidRuleSetExceptionIsThrownOnRcUpdate()
      throws IOException {
    // setup
    ConfigKey key = ParsedConfigKey.parse("datadog/1234/ASM_DD/ID/config");
    AppSecConfigServiceImpl service = new AppSecConfigServiceImpl(config, poller, reconf);
    AppSecSystem.setActive(true);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.FULLY_ENABLED);
    AppSecModule wafModule = mock(AppSecModule.class);
    when(wafModule.isWafBuilderSet()).thenReturn(false);
    service.modulesToUpdateVersionIn(singletonList(wafModule));

    // when
    service.maybeSubscribeConfigPolling();

    // then
    ProductListener wafDataChangesListener = verifyProductListenerAdded(Product.ASM_DD);
    String invalidRuleConfig = readResource("config_service/invalid_rule_config.json");

    // when
    assertThrows(
        RuntimeException.class,
        () -> wafDataChangesListener.accept(key, invalidRuleConfig.getBytes(UTF_8), NOOP));

    // then
    assertEquals("1.99.0", service.getCurrentRuleVersion());
    verify(wafModule).setWafBuilder(any());
    verify(wafModule).setRuleVersion("1.99.0");
  }

  @Test
  void configKeysAreAddedAndRemovedToTheSetWhenReceivingAsmDdPayloads() throws Exception {
    // setup
    ConfigKey key = ParsedConfigKey.parse("datadog/1234/ASM_DD/ID/config");
    AppSecConfigServiceImpl service = new AppSecConfigServiceImpl(config, poller, reconf);
    // AppSec must be active for the ASM_DD subscription
    AppSecSystem.setActive(true);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.ENABLED_INACTIVE);

    // when
    service.maybeSubscribeConfigPolling();

    // then
    ProductListener wafDataChangesListener = verifyProductListenerAdded(Product.ASM_DD);
    FeaturesListeners featuresListeners = verifyFeaturesListenerAdded();

    // when
    featuresListeners.accept("asm_features conf", ASM_ENABLED);

    // then
    assertTrue(service.usedDDWafConfigKeys.isEmpty());

    // when
    wafDataChangesListener.accept(key, RULES_OVERRIDE_CONFIG.getBytes(UTF_8), NOOP);

    // then
    assertEquals(singletonList(key.toString()), new ArrayList<>(service.usedDDWafConfigKeys));

    // when
    wafDataChangesListener.remove(key, NOOP);

    // then
    assertTrue(service.usedDDWafConfigKeys.isEmpty());
  }

  @Test
  void testThatEmptyConfigurationsAreAcknowledged() {
    // given
    ConfigKey key = ParsedConfigKey.parse("datadog/1234/ASM_DD/ID/config");

    // when
    AppSecSystem.setActive(true);
    when(config.getAppSecActivation()).thenReturn(ProductActivation.FULLY_ENABLED);
    AppSecConfigServiceImpl service = new AppSecConfigServiceImpl(config, poller, reconf);
    service.init();
    service.maybeSubscribeConfigPolling();

    // then
    ProductListener wafDataChangesListener = verifyProductListenerAdded(Product.ASM_DATA);
    ConfigurationEndListener confEndListener = verifyConfigurationEndListenerAdded();

    // when / then
    assertDoesNotThrow(() -> {
      wafDataChangesListener.accept(key, "{}".getBytes(UTF_8), NOOP);
      confEndListener.onConfigurationEnd();
    });

    // when / then
    assertDoesNotThrow(() -> {
      wafDataChangesListener.accept(key, null, NOOP);
      confEndListener.onConfigurationEnd();
    });
  }

  /**
   * Tests how {@link AppSecConfigServiceImpl} handles rejected and removed ASM_DD configs against
   * the real WAF.
   */
  @Nested
  class RejectedUpdates {

    private static final String DEFAULT_CONFIG_KEY = "ASM_DD/default";

    private final ConfigKey key = ParsedConfigKey.parse("datadog/2/ASM_DD/test-config/config");
    private final ConfigKey otherKey =
        ParsedConfigKey.parse("datadog/2/ASM_DD/other-config/config");

    private String validConfig;
    private String invalidVersionConfig;
    private String otherValidConfig;

    private ProductListener asmDdListener;
    private ConfigurationEndListener configurationEndListener;

    @BeforeEach
    void setUp() throws IOException {
      validConfig = readResource("config_service/valid_config.json");
      invalidVersionConfig = validConfig.replace("\"version\": \"2.2\"", "\"version\": \"abc\"");
      otherValidConfig = validConfig.replace("\"id\": \"test-rule\"", "\"id\": \"other-rule\"");
      AppSecSystem.setActive(true);
      when(config.getAppSecActivation()).thenReturn(ProductActivation.FULLY_ENABLED);
      // keep the global user id collection mode untouched by the configuration-end listener
      when(config.getAppSecUserIdCollectionMode()).thenReturn(UserIdCollectionMode.get());

      appSecConfigService.init();
      appSecConfigService.maybeSubscribeConfigPolling();

      asmDdListener = verifyProductListenerAdded(Product.ASM_DD);
      configurationEndListener = verifyConfigurationEndListenerAdded();
    }

    @Test
    void defaultRulesAreRestoredAfterRemovingARejectedUpdate() throws Exception {
      asmDdListener.accept(key, validConfig.getBytes(UTF_8), NOOP);
      acceptRejectedUpdate(key);

      assertDoesNotThrow(() -> asmDdListener.remove(key, NOOP));
      configurationEndListener.onConfigurationEnd();

      // only succeeds if the default config was registered again
      assertDoesNotThrow(
          () -> appSecConfigService.getWafBuilder().removeConfig(DEFAULT_CONFIG_KEY));
    }

    @Test
    void defaultRulesAreRestoredWhileARejectedUpdateIsStillPublished() throws Exception {
      asmDdListener.accept(key, validConfig.getBytes(UTF_8), NOOP);
      acceptRejectedUpdate(key);

      configurationEndListener.onConfigurationEnd();

      // only succeeds if the default config was registered again
      assertDoesNotThrow(
          () -> appSecConfigService.getWafBuilder().removeConfig(DEFAULT_CONFIG_KEY));
    }

    @Test
    void defaultRulesStayRemovedWhileAnotherConfigIsApplied() throws Exception {
      asmDdListener.accept(otherKey, otherValidConfig.getBytes(UTF_8), NOOP);
      asmDdListener.accept(key, validConfig.getBytes(UTF_8), NOOP);
      acceptRejectedUpdate(key);

      configurationEndListener.onConfigurationEnd();

      // the default config is not registered: otherKey still provides the rules
      assertThrows(
          UnclassifiedWafException.class,
          () -> appSecConfigService.getWafBuilder().removeConfig(DEFAULT_CONFIG_KEY));
    }

    @Test
    void removingAKeyThatWasNeverAppliedDoesNotThrow() {
      assertDoesNotThrow(() -> asmDdListener.remove(key, NOOP));
    }

    /** The WAF rejects the update and, since libddwaf 2.2.0, unregisters the path. */
    private void acceptRejectedUpdate(ConfigKey configKey) {
      RuntimeException exception =
          assertThrows(
              RuntimeException.class,
              () -> asmDdListener.accept(configKey, invalidVersionConfig.getBytes(UTF_8), NOOP));
      assertInstanceOf(InvalidRuleSetException.class, exception.getCause());
    }
  }

  /** The ASM_FEATURES deserializer and listener registered on the poller. */
  private static final class FeaturesListeners {
    final ConfigurationDeserializer<AppSecFeatures> deserializer;
    final ConfigurationChangesTypedListener<AppSecFeatures> listener;

    FeaturesListeners(
        ConfigurationDeserializer<AppSecFeatures> deserializer,
        ConfigurationChangesTypedListener<AppSecFeatures> listener) {
      this.deserializer = deserializer;
      this.listener = listener;
    }

    /** Deserializes the given JSON features ({@code null} to unset them) and applies them. */
    void accept(String configKey, String json) throws IOException {
      AppSecFeatures features =
          json == null ? null : deserializer.deserialize(json.getBytes(UTF_8));
      listener.accept(configKey, features, NOOP);
    }
  }

  private void commitWafSubconfigListener() {
    AppSecConfigService.TransactionalAppSecModuleConfigurer configurer =
        appSecConfigService.createAppSecModuleConfigurer();
    configurer.addSubConfigListener("waf", subconfigListener);
    configurer.commit();
  }

  private ProductListener verifyProductListenerAdded(Product product) {
    ArgumentCaptor<ProductListener> captor = ArgumentCaptor.forClass(ProductListener.class);
    verify(poller).addListener(eq(product), captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private FeaturesListeners verifyFeaturesListenerAdded() {
    ArgumentCaptor<ConfigurationDeserializer> deserializerCaptor =
        ArgumentCaptor.forClass(ConfigurationDeserializer.class);
    ArgumentCaptor<ConfigurationChangesTypedListener> listenerCaptor =
        ArgumentCaptor.forClass(ConfigurationChangesTypedListener.class);
    verify(poller)
        .addListener(
            eq(Product.ASM_FEATURES), deserializerCaptor.capture(), listenerCaptor.capture());
    return new FeaturesListeners(deserializerCaptor.getValue(), listenerCaptor.getValue());
  }

  private ConfigurationEndListener verifyConfigurationEndListenerAdded() {
    ArgumentCaptor<ConfigurationEndListener> captor =
        ArgumentCaptor.forClass(ConfigurationEndListener.class);
    verify(poller).addConfigurationEndListener(captor.capture());
    return captor.getValue();
  }

  /**
   * Verifies the exact number of listeners added on the poller, for every overload. Typed {@code
   * any()} matchers are used so that calls with {@code null} arguments are counted too.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private void verifyAddListenerCalls(int productListeners, int typedListeners) {
    verify(poller, times(productListeners)).addListener(any(), (ProductListener) any());
    verify(poller, times(typedListeners))
        .addListener(
            any(), (ConfigurationDeserializer) any(), (ConfigurationChangesTypedListener) any());
    verify(poller, never()).addListener(any(), (ConfigurationChangesListener) any());
    verify(poller, never()).addListener(any(), (String) any(), (ProductListener) any());
    verify(poller, never())
        .addListener(
            any(),
            (String) any(),
            (ConfigurationDeserializer) any(),
            (ConfigurationChangesTypedListener) any());
  }

  private Path resourcePath(String resource) throws Exception {
    return Paths.get(getClass().getClassLoader().getResource(resource).toURI());
  }

  private static AppSecFeatures autoUserInstrum(String mode) {
    AppSecFeatures features = new AppSecFeatures();
    features.autoUserInstrum = new AppSecFeatures.AutoUserInstrum();
    features.autoUserInstrum.mode = mode;
    return features;
  }
}
