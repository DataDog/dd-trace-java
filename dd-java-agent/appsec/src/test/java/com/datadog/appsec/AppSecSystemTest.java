package com.datadog.appsec;

import static com.datadog.appsec.TestResources.readResource;
import static com.datadog.appsec.gateway.AppSecRequestContextTestBridge.getRequestHeaders;
import static com.datadog.appsec.gateway.AppSecRequestContextTestBridge.getResponseHeaders;
import static com.datadog.appsec.gateway.AppSecRequestContextTestBridge.transferCollectedEvents;
import static datadog.trace.api.config.AppSecConfig.APPSEC_IP_ADDR_HEADER;
import static datadog.trace.api.config.AppSecConfig.APPSEC_RULES_FILE;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.api.gateway.RequestContextSlot.APPSEC;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.report.AppSecEvent;
import com.datadog.appsec.util.AbortStartupException;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.metrics.api.Monitoring;
import datadog.remoteconfig.ConfigurationEndListener;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.TagMap;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiFunction;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AppSecSystemTest extends DDJavaSpecification {

  private final SubscriptionService subService = mock(SubscriptionService.class);
  private final ConfigurationPoller poller = mock(ConfigurationPoller.class);

  @AfterEach
  void tearDown() {
    AppSecSystem.stop();
  }

  @Test
  void registersPowerwafModule() {
    // when
    AppSecSystem.start(subService, sharedCommunicationObjects());

    // then
    assertTrue(AppSecSystem.getStartedModulesInfo().contains("ddwaf"));
  }

  @Test
  @WithConfig(key = APPSEC_RULES_FILE, value = "/file/that/does/not/exist")
  void throwsIfCustomConfigDoesNotExist() {
    // when
    AbortStartupException exception =
        assertThrows(
            AbortStartupException.class,
            () -> AppSecSystem.start(subService, sharedCommunicationObjects()));

    // then
    assertTrue(exception.getCause().toString().contains("/file/that/does/not/exist"));
  }

  @Test
  void systemShouldThrowAbortStartupExceptionWhenConfigFileIsNotValidJson(@TempDir Path tempDir)
      throws IOException {
    // given: 'a temporary file with invalid JSON content'
    Path path = tempDir.resolve("dd-trace-rules.json");
    Files.write(path, "{".getBytes(UTF_8)); // Invalid JSON - missing closing brace
    injectSysConfig(APPSEC_RULES_FILE, path.toString());

    // when: 'starting the AppSec system'
    AbortStartupException exception =
        assertThrows(
            AbortStartupException.class,
            () -> AppSecSystem.start(subService, sharedCommunicationObjects()));

    // then: 'an AbortStartupException should be thrown'
    assertInstanceOf(IOException.class, exception.getCause());
  }

  @Test
  @WithConfig(key = APPSEC_IP_ADDR_HEADER, value = "foo-bar")
  void honorsAppsecIpheader() {
    // unfortunately the way to test this is honored requires us to get into the weeds
    RequestContext requestContext = mock(RequestContext.class);
    TraceSegment traceSegment = mock(TraceSegment.class);
    AppSecRequestContext appSecReqCtx = mock(AppSecRequestContext.class);
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTags()).thenReturn(TagMap.fromMap(singletonMap("http.client_ip", "1.1.1.1")));
    when(requestContext.<AppSecRequestContext>getData(APPSEC)).thenReturn(appSecReqCtx);
    when(requestContext.getTraceSegment()).thenReturn(traceSegment);
    when(transferCollectedEvents(appSecReqCtx)).thenReturn(singletonList(mock(AppSecEvent.class)));
    when(getRequestHeaders(appSecReqCtx))
        .thenReturn(singletonMap("foo-bar", singletonList("1.1.1.1")));
    when(getResponseHeaders(appSecReqCtx)).thenReturn(emptyMap());

    // when
    AppSecSystem.start(subService, sharedCommunicationObjects());
    requestEndedCallback().apply(requestContext, span);

    // then
    verify(span).getTags();
    verify(requestContext).getData(APPSEC);
    verify(requestContext).getTraceSegment();
    transferCollectedEvents(verify(appSecReqCtx));
    getRequestHeaders(verify(appSecReqCtx));
    getResponseHeaders(verify(appSecReqCtx));
    verify(span).setTag("actor.ip", "1.1.1.1");
  }

  @Test
  void whenNotStartedReturnsEmptyListForStartedModules() {
    // expect
    assertTrue(AppSecSystem.getStartedModulesInfo().isEmpty());
  }

  @Test
  void updatingConfigurationReplacesTheEventProducer() throws IOException {
    // when
    AppSecSystem.start(subService, sharedCommunicationObjects());
    EventProducerService initialEPS = AppSecSystem.currentEventProducer();

    // then
    ArgumentCaptor<ProductListener> asmListenerCaptor =
        ArgumentCaptor.forClass(ProductListener.class);
    verify(poller).addListener(eq(Product.ASM_DD), asmListenerCaptor.capture());
    ArgumentCaptor<ConfigurationEndListener> confEndListenerCaptor =
        ArgumentCaptor.forClass(ConfigurationEndListener.class);
    verify(poller).addConfigurationEndListener(confEndListenerCaptor.capture());

    // when
    asmListenerCaptor
        .getValue()
        .accept(
            mock(ConfigKey.class),
            readResource("appsec_system/asm_dd_config.json").getBytes(UTF_8),
            null);
    confEndListenerCaptor.getValue().onConfigurationEnd();

    // then
    assertNotSame(initialEPS, AppSecSystem.currentEventProducer());
  }

  @SuppressWarnings("unchecked")
  private BiFunction<RequestContext, IGSpanInfo, Flow<Void>> requestEndedCallback() {
    ArgumentCaptor<BiFunction<RequestContext, IGSpanInfo, Flow<Void>>> captor =
        ArgumentCaptor.forClass(BiFunction.class);
    verify(subService).registerCallback(eq(EVENTS.requestEnded()), captor.capture());
    return captor.getValue();
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
