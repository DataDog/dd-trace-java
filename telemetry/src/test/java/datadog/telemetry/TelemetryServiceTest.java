package datadog.telemetry;

import static datadog.telemetry.TelemetryClient.Result.FAILURE;
import static datadog.telemetry.TelemetryClient.Result.NOT_FOUND;
import static datadog.telemetry.TelemetryClient.Result.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.LogMessageLevel;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.config.AppSecConfig;
import datadog.trace.api.config.DebuggerConfig;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.telemetry.Endpoint;
import datadog.trace.api.telemetry.ProductChange;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import datadog.trace.util.ConfigStrings;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class TelemetryServiceTest {

  private final ConfigOrigin confKeyOrigin = ConfigOrigin.DEFAULT;
  private final ConfigSetting confKeyValue =
      ConfigSetting.of("confkey", "confvalue", confKeyOrigin);
  private final Map<ConfigOrigin, Map<String, ConfigSetting>> configuration =
      Collections.singletonMap(confKeyOrigin, Collections.singletonMap("confkey", confKeyValue));
  private final Integration integration = new Integration("integration", true);
  private final Dependency dependency = new Dependency("dependency", "1.0.0", "src", "hash");
  private final Metric metric =
      new Metric()
          .namespace("tracers")
          .metric("metric")
          .points(Collections.<List<Number>>singletonList(Arrays.<Number>asList(1, 2)))
          .tags(Arrays.asList("tag1", "tag2"));
  private final DistributionSeries distribution =
      new DistributionSeries()
          .namespace("tracers")
          .metric("distro")
          .points(Arrays.asList(1, 2, 3))
          .tags(Arrays.asList("tag1", "tag2"))
          .common(false);
  private final LogMessage logMessage =
      new LogMessage()
          .message("log-message")
          .tags("tag1:tag2")
          .level(LogMessageLevel.DEBUG)
          .stackTrace("stack-trace")
          .tracerTime(32423L)
          .count(1);
  private final ProductChange productChange =
      new ProductChange().productType(ProductChange.ProductType.APPSEC).enabled(true);
  private final Endpoint endpoint =
      new Endpoint()
          .first(true)
          .type("REST")
          .method("GET")
          .operation("http.request")
          .resource("GET /test")
          .path("/test")
          .requestBodyType(Collections.singletonList("application/json"))
          .responseBodyType(Collections.singletonList("application/json"))
          .responseCode(Collections.singletonList(200))
          .authentication(Collections.singletonList("JWT"));

  @Test
  void happyPathWithoutData() throws IOException {
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false);

    // first iteration
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendAppStartedEvent();

    // app-started
    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products();
    testHttpClient.assertNoMoreRequests();

    // second iteration
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendTelemetryEvents();

    // app-heartbeat only
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT);
    testHttpClient.assertNoMoreRequests();

    // third iteration
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendTelemetryEvents();

    // app-heartbeat only
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT);
    testHttpClient.assertNoMoreRequests();
  }

  @Test
  void happyPathWithData() throws IOException {
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false);

    // add data before first iteration
    telemetryService.addConfiguration(configuration);
    telemetryService.addIntegration(integration);
    telemetryService.addDependency(dependency);
    telemetryService.addMetric(metric);
    telemetryService.addDistributionSeries(distribution);
    telemetryService.addLogMessage(logMessage);
    telemetryService.addProductChange(productChange);
    telemetryService.addEndpoint(endpoint);

    // send messages
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendAppStartedEvent();

    testHttpClient
        .assertRequestBody(RequestType.APP_STARTED)
        .assertPayload()
        .products()
        .configuration(Collections.singletonList(confKeyValue));

    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendTelemetryEvents();

    testHttpClient
        .assertRequestBody(RequestType.MESSAGE_BATCH)
        .assertBatch(8)
        .assertFirstMessage(RequestType.APP_HEARTBEAT)
        .hasNoPayload()
        // no configuration here as it has already been sent with the app-started event
        .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE)
        .hasPayload()
        .integrations(Collections.singletonList(integration))
        .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED)
        .hasPayload()
        .dependencies(Collections.singletonList(dependency))
        .assertNextMessage(RequestType.GENERATE_METRICS)
        .hasPayload()
        .namespace("tracers")
        .metrics(Collections.singletonList(metric))
        .assertNextMessage(RequestType.DISTRIBUTIONS)
        .hasPayload()
        .namespace("tracers")
        .distributionSeries(Collections.singletonList(distribution))
        .assertNextMessage(RequestType.LOGS)
        .hasPayload()
        .logs(Collections.singletonList(logMessage))
        .assertNextMessage(RequestType.APP_PRODUCT_CHANGE)
        .hasPayload()
        .productChange(productChange)
        .assertNextMessage(RequestType.APP_ENDPOINTS)
        .hasPayload()
        .endpoint(endpoint)
        .assertNoMoreMessages();
    testHttpClient.assertNoMoreRequests();

    // second iteration heartbeat only
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendTelemetryEvents();

    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT).assertNoPayload();
    testHttpClient.assertNoMoreRequests();

    // third iteration metrics data
    telemetryService.addMetric(metric);
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendTelemetryEvents();

    testHttpClient
        .assertRequestBody(RequestType.MESSAGE_BATCH)
        .assertBatch(2)
        .assertFirstMessage(RequestType.APP_HEARTBEAT)
        .hasNoPayload()
        .assertNextMessage(RequestType.GENERATE_METRICS)
        .hasPayload()
        .namespace("tracers")
        .metrics(Collections.singletonList(metric))
        .assertNoMoreMessages();
    testHttpClient.assertNoMoreRequests();
  }

  @Test
  void happyPathWithDataAfterAppStarted() throws IOException {
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false);

    // send messages
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendAppStartedEvent();

    testHttpClient.assertRequestBody(RequestType.APP_STARTED).assertPayload().products();
    testHttpClient.assertNoMoreRequests();

    // add data after first iteration
    telemetryService.addConfiguration(configuration);
    telemetryService.addIntegration(integration);
    telemetryService.addDependency(dependency);
    telemetryService.addMetric(metric);
    telemetryService.addDistributionSeries(distribution);
    telemetryService.addLogMessage(logMessage);
    telemetryService.addProductChange(productChange);
    telemetryService.addEndpoint(endpoint);

    // send messages
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendTelemetryEvents();

    testHttpClient
        .assertRequestBody(RequestType.MESSAGE_BATCH)
        .assertBatch(9)
        .assertFirstMessage(RequestType.APP_HEARTBEAT)
        .hasNoPayload()
        .assertNextMessage(RequestType.APP_CLIENT_CONFIGURATION_CHANGE)
        .hasPayload()
        .configuration(Collections.singletonList(confKeyValue))
        .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE)
        .hasPayload()
        .integrations(Collections.singletonList(integration))
        .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED)
        .hasPayload()
        .dependencies(Collections.singletonList(dependency))
        .assertNextMessage(RequestType.GENERATE_METRICS)
        .hasPayload()
        .namespace("tracers")
        .metrics(Collections.singletonList(metric))
        .assertNextMessage(RequestType.DISTRIBUTIONS)
        .hasPayload()
        .namespace("tracers")
        .distributionSeries(Collections.singletonList(distribution))
        .assertNextMessage(RequestType.LOGS)
        .hasPayload()
        .logs(Collections.singletonList(logMessage))
        .assertNextMessage(RequestType.APP_PRODUCT_CHANGE)
        .hasPayload()
        .productChange(productChange)
        .assertNextMessage(RequestType.APP_ENDPOINTS)
        .hasPayload()
        .endpoint(endpoint)
        .assertNoMoreMessages();
    testHttpClient.assertNoMoreRequests();
  }

  @Test
  void doNotDiscardDataForAppStartedEventUntilItHasBeenSuccessfullySent() throws IOException {
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false);
    telemetryService.addConfiguration(configuration);

    // attempt with 404 error
    testHttpClient.expectRequest(NOT_FOUND);
    assertFalse(telemetryService.sendAppStartedEvent());

    // app-started is attempted
    testHttpClient
        .assertRequestBody(RequestType.APP_STARTED)
        .assertPayload()
        .products()
        .configuration(Collections.singletonList(confKeyValue));
    testHttpClient.assertNoMoreRequests();

    // attempt with 500 error
    testHttpClient.expectRequest(FAILURE);
    assertFalse(telemetryService.sendAppStartedEvent());

    // app-started is attempted
    testHttpClient
        .assertRequestBody(RequestType.APP_STARTED)
        .assertPayload()
        .products()
        .configuration(Collections.singletonList(confKeyValue));
    testHttpClient.assertNoMoreRequests();

    // attempt with unexpected FAILURE (not valid)
    testHttpClient.expectRequest(FAILURE);
    assertFalse(telemetryService.sendAppStartedEvent());

    // app-started is attempted
    testHttpClient
        .assertRequestBody(RequestType.APP_STARTED)
        .assertPayload()
        .products()
        .configuration(Collections.singletonList(confKeyValue));
    testHttpClient.assertNoMoreRequests();

    // attempt with success
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendAppStartedEvent();

    // app-started is attempted
    testHttpClient
        .assertRequestBody(RequestType.APP_STARTED)
        .assertPayload()
        .products()
        .configuration(Collections.singletonList(confKeyValue));
    testHttpClient.assertNoMoreRequests();
  }

  @Test
  void resendDataOnSuccessfulAttemptAfterAFailure() throws IOException {
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false);

    telemetryService.addConfiguration(configuration);
    telemetryService.addIntegration(integration);
    telemetryService.addDependency(dependency);
    telemetryService.addMetric(metric);
    telemetryService.addDistributionSeries(distribution);
    telemetryService.addLogMessage(logMessage);
    telemetryService.addProductChange(productChange);
    telemetryService.addEndpoint(endpoint);

    // attempt with NOT_FOUND error
    testHttpClient.expectRequest(NOT_FOUND);
    assertFalse(telemetryService.sendAppStartedEvent());

    // app-started attempted with config
    testHttpClient
        .assertRequestBody(RequestType.APP_STARTED)
        .assertPayload()
        .products()
        .configuration(Collections.singletonList(confKeyValue));
    testHttpClient.assertNoMoreRequests();

    // successful app-started attempt
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendAppStartedEvent();

    // attempt app-started with SUCCESS
    testHttpClient
        .assertRequestBody(RequestType.APP_STARTED)
        .assertPayload()
        .products()
        .configuration(Collections.singletonList(confKeyValue));

    // successful batch attempt
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendTelemetryEvents();

    // attempt batch with SUCCESS
    testHttpClient
        .assertRequestBody(RequestType.MESSAGE_BATCH)
        .assertBatch(8)
        .assertFirstMessage(RequestType.APP_HEARTBEAT)
        .hasNoPayload()
        // no configuration here as it has already been sent with the app-started event
        .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE)
        .hasPayload()
        .integrations(Collections.singletonList(integration))
        .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED)
        .hasPayload()
        .dependencies(Collections.singletonList(dependency))
        .assertNextMessage(RequestType.GENERATE_METRICS)
        .hasPayload()
        .namespace("tracers")
        .metrics(Collections.singletonList(metric))
        .assertNextMessage(RequestType.DISTRIBUTIONS)
        .hasPayload()
        .namespace("tracers")
        .distributionSeries(Collections.singletonList(distribution))
        .assertNextMessage(RequestType.LOGS)
        .hasPayload()
        .logs(Collections.singletonList(logMessage))
        .assertNextMessage(RequestType.APP_PRODUCT_CHANGE)
        .hasPayload()
        .productChange(productChange)
        .assertNextMessage(RequestType.APP_ENDPOINTS)
        .hasPayload()
        .endpoint(endpoint)
        .assertNoMoreMessages();
    testHttpClient.assertNoMoreRequests();

    // attempt with NOT_FOUND error
    testHttpClient.expectRequest(NOT_FOUND);
    telemetryService.sendTelemetryEvents();

    // message-batch attempted with heartbeat
    testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT).assertNoPayload();
    testHttpClient.assertNoMoreRequests();
  }

  @Test
  void sendClosingEventRequest() throws IOException {
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false);

    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendAppClosingEvent();

    testHttpClient.assertRequestBody(RequestType.APP_CLOSING);
    testHttpClient.assertNoMoreRequests();
  }

  @TableTest({
    "scenario                    | otelEnabled | otEnabled | expectedWarnings",
    "both otel and ot enabled    | true        | true      | 1               ",
    "only otel enabled           | true        | false     | 0               ",
    "only ot enabled             | false       | true      | 0               ",
    "neither otel nor ot enabled | false       | false     | 0               "
  })
  void reportWhenBothOTelAndOTAreEnabled(
      boolean otelEnabled, boolean otEnabled, int expectedWarnings) {
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = spy(new TelemetryService(testHttpClient, 1000, false));
    Integration otel = new Integration("opentelemetry-1", otelEnabled);
    Integration ot = new Integration("opentracing", otEnabled);

    telemetryService.addIntegration(otel);

    verify(telemetryService, times(0)).warnAboutExclusiveIntegrations();

    telemetryService.addIntegration(ot);

    verify(telemetryService, times(expectedWarnings)).warnAboutExclusiveIntegrations();
  }

  @Test
  void splitTelemetryRequestsIfTheSizeAboveTheLimit() throws IOException {
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 5000, false);

    // send a heartbeat request without telemetry data to measure body size to set stable request
    // size limit
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendTelemetryEvents();

    // get body size
    int bodySize = testHttpClient.assertRequestBody(RequestType.APP_HEARTBEAT).bodySize();
    assertTrue(bodySize > 0);

    // sending first part of data
    telemetryService = new TelemetryService(testHttpClient, bodySize + 512, false);

    telemetryService.addConfiguration(configuration);
    telemetryService.addIntegration(integration);
    telemetryService.addDependency(dependency);
    telemetryService.addMetric(metric);
    telemetryService.addDistributionSeries(distribution);
    telemetryService.addLogMessage(logMessage);
    telemetryService.addProductChange(productChange);
    telemetryService.addEndpoint(endpoint);

    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendTelemetryEvents();

    // attempt with SUCCESS
    testHttpClient
        .assertRequestBody(RequestType.MESSAGE_BATCH)
        .assertBatch(5)
        .assertFirstMessage(RequestType.APP_HEARTBEAT)
        .hasNoPayload()
        .assertNextMessage(RequestType.APP_CLIENT_CONFIGURATION_CHANGE)
        .hasPayload()
        .configuration(Collections.singletonList(confKeyValue))
        .assertNextMessage(RequestType.APP_INTEGRATIONS_CHANGE)
        .hasPayload()
        .integrations(Collections.singletonList(integration))
        .assertNextMessage(RequestType.APP_DEPENDENCIES_LOADED)
        .hasPayload()
        .dependencies(Collections.singletonList(dependency))
        .assertNextMessage(RequestType.GENERATE_METRICS)
        .hasPayload()
        .namespace("tracers")
        .metrics(Collections.singletonList(metric))
        // no more data fit this message is sent in the next message
        .assertNoMoreMessages();

    // sending second part of data
    testHttpClient.expectRequest(SUCCESS);
    assertFalse(telemetryService.sendTelemetryEvents());

    testHttpClient
        .assertRequestBody(RequestType.MESSAGE_BATCH)
        .assertBatch(5)
        .assertFirstMessage(RequestType.APP_HEARTBEAT)
        .hasNoPayload()
        .assertNextMessage(RequestType.DISTRIBUTIONS)
        .hasPayload()
        .namespace("tracers")
        .distributionSeries(Collections.singletonList(distribution))
        .assertNextMessage(RequestType.LOGS)
        .hasPayload()
        .logs(Collections.singletonList(logMessage))
        .assertNextMessage(RequestType.APP_PRODUCT_CHANGE)
        .hasPayload()
        .productChange(productChange)
        .assertNextMessage(RequestType.APP_ENDPOINTS)
        .hasPayload()
        .endpoint(endpoint)
        .assertNoMoreMessages();
    testHttpClient.assertNoMoreRequests();
  }

  @Test
  void sendAllCollectedDataWithExtendedHeartbeatRequestEveryTime() throws IOException {
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false);

    telemetryService.addConfiguration(configuration);
    telemetryService.addIntegration(integration);
    telemetryService.addDependency(dependency);

    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendExtendedHeartbeat();

    testHttpClient
        .assertRequestBody(RequestType.APP_EXTENDED_HEARTBEAT)
        .assertPayload()
        .configuration(Collections.singletonList(confKeyValue))
        .integrations(Collections.singletonList(integration))
        .dependencies(Collections.singletonList(dependency));
    testHttpClient.assertNoMoreRequests();

    telemetryService.addConfiguration(configuration);
    telemetryService.addIntegration(integration);
    telemetryService.addDependency(dependency);

    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendExtendedHeartbeat();

    testHttpClient
        .assertRequestBody(RequestType.APP_EXTENDED_HEARTBEAT)
        .assertPayload()
        .configuration(Arrays.asList(confKeyValue, confKeyValue))
        .integrations(Arrays.asList(integration, integration))
        .dependencies(Arrays.asList(dependency, dependency));
    testHttpClient.assertNoMoreRequests();
  }

  @TableTest({
    "scenario | resultCode",
    "success  | SUCCESS   ",
    "failure  | FAILURE   ",
    "notFound | NOT_FOUND "
  })
  void
      sendExtendedHeartbeatRequestEvenIfDataAlreadyHasBeenSentOrAttemptedAsPartOfAnotherTelemetryEvents(
          String resultCode) throws IOException {
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false);

    telemetryService.addConfiguration(configuration);
    telemetryService.addIntegration(integration);
    telemetryService.addDependency(dependency);

    testHttpClient.expectRequest(TelemetryClient.Result.valueOf(resultCode));
    telemetryService.sendTelemetryEvents();

    testHttpClient.assertRequestBody(RequestType.MESSAGE_BATCH);

    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendExtendedHeartbeat();

    testHttpClient
        .assertRequestBody(RequestType.APP_EXTENDED_HEARTBEAT)
        .assertPayload()
        .configuration(Collections.singletonList(confKeyValue))
        .integrations(Collections.singletonList(integration))
        .dependencies(Collections.singletonList(dependency));
    testHttpClient.assertNoMoreRequests();
  }

  @TableTest({
    "scenario    | id ",
    "with value  | foo",
    "null value  |    ",
    "empty value | '' "
  })
  void appCanPropagateConfigurationId(String id) throws IOException {
    String instrumentationConfigIdKey = "instrumentation_config_id";
    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false);
    Map<String, ConfigSetting> configMap =
        Collections.singletonMap(
            instrumentationConfigIdKey,
            ConfigSetting.of(instrumentationConfigIdKey, id, ConfigOrigin.ENV));
    telemetryService.addConfiguration(Collections.singletonMap(ConfigOrigin.ENV, configMap));

    // first iteration
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendAppStartedEvent();

    // app-started
    testHttpClient
        .assertRequestBody(RequestType.APP_STARTED)
        .assertPayload()
        .instrumentationConfigId(id);
    testHttpClient.assertNoMoreRequests();
  }

  @TableTest({
    "scenario                  | installId                            | installType     | installTime",
    "no install data           |                                      |                 |            ",
    "install time only         |                                      |                 | 1703188334 ",
    "install type only         |                                      | k8s_single_step |            ",
    "install type and time     |                                      | k8s_single_step | 1703188212 ",
    "install id only           | 68e75c99-57ca-4a12-adfc-575c4b05fcbe |                 |            ",
    "install id and time       | 68e75c48-57ca-4a12-adfc-575c4b05bfff |                 | 1704183412 ",
    "install id and type       | 68e75c55-57ca-4a12-adfc-575c4b05aaaa | k8s_single_step |            ",
    "install id, type and time | 68e75c77-57ca-4a12-adfc-575c4b05fc44 | k8s_single_step | 1993188215 "
  })
  void appStartedMustHaveInstallSignature(String installId, String installType, String installTime)
      throws IOException {
    WithConfigExtension.injectEnvConfig("INSTRUMENTATION_INSTALL_ID", installId);
    WithConfigExtension.injectEnvConfig("INSTRUMENTATION_INSTALL_TYPE", installType);
    WithConfigExtension.injectEnvConfig("INSTRUMENTATION_INSTALL_TIME", installTime);

    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false);

    // first iteration
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendAppStartedEvent();

    // app-started
    testHttpClient
        .assertRequestBody(RequestType.APP_STARTED)
        .assertPayload()
        .installSignature(installId, installType, installTime);
    testHttpClient.assertNoMoreRequests();
  }

  @TableTest({
    "scenario                              | appsecConfig | appsecEnabled | profilingConfig | profilingEnabled | dynInstrConfig | dynInstrEnabled",
    "all products enabled                  | 1            | true          | 1               | true             | 1              | true           ",
    "dynamic instrumentation disabled      | 1            | true          | 1               | true             | 0              | false          ",
    "profiling disabled                    | 1            | true          | 0               | false            | 1              | true           ",
    "profiling and dyn instr disabled      | 1            | true          | 0               | false            | 0              | false          ",
    "all products disabled                 | 0            | false         | 0               | false            | 0              | false          ",
    "appsec inactive value treated enabled | inactive     | true          | 0               | false            | 0              | false          "
  })
  void appStartedMustIncludeActivatedProductsInfo(
      String appsecConfig,
      boolean appsecEnabled,
      String profilingConfig,
      boolean profilingEnabled,
      String dynInstrConfig,
      boolean dynInstrEnabled)
      throws IOException {
    WithConfigExtension.injectEnvConfig(
        ConfigStrings.toEnvVar(AppSecConfig.APPSEC_ENABLED), appsecConfig);
    WithConfigExtension.injectEnvConfig(
        ConfigStrings.toEnvVar(ProfilingConfig.PROFILING_ENABLED), profilingConfig);
    WithConfigExtension.injectEnvConfig(
        ConfigStrings.toEnvVar(DebuggerConfig.DYNAMIC_INSTRUMENTATION_ENABLED), dynInstrConfig);

    TestTelemetryRouter testHttpClient = new TestTelemetryRouter();
    TelemetryService telemetryService = new TelemetryService(testHttpClient, 10000, false);

    // first iteration
    testHttpClient.expectRequest(SUCCESS);
    telemetryService.sendAppStartedEvent();

    // app-started
    testHttpClient
        .assertRequestBody(RequestType.APP_STARTED)
        .assertPayload()
        .products(appsecEnabled, profilingEnabled, dynInstrEnabled);
    testHttpClient.assertNoMoreRequests();
  }
}
