package datadog.trace.common.writer;

import static datadog.trace.agent.test.utils.OtelMetricCollector.collect;
import static datadog.trace.core.monitor.OtelExporterId.Signal.SPAN;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.nCopies;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.OkHttpUtils;
import datadog.metrics.api.Histograms;
import datadog.metrics.api.Monitoring;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.agent.test.utils.OtelMetricCollector;
import datadog.trace.agent.test.utils.OtelMetricCollector.DataPointEntry;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.intake.TrackType;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentMapperDiscovery;
import datadog.trace.common.writer.ddintake.DDIntakeApi;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.ExportRecorder;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.OtelComponentId;
import datadog.trace.core.monitor.OtelExporterId;
import datadog.trace.core.otlp.sdk.OtelSdkSelfMonitoring;
import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * The span exporter metrics of the Datadog Agent and intake exporters. How a response maps to
 * {@code error.type} and the status attributes is covered by {@code OtelHealthMetricsExportTest},
 * and what each API returns by its own tests; this covers the dispatcher's send wrapping, the
 * destination, and the writers' exporter identities.
 */
class SpanExporterMetricsTest extends DDCoreJavaSpecification {
  private static final String INFLIGHT = "otel.sdk.exporter.span.inflight";
  private static final String EXPORTED = "otel.sdk.exporter.span.exported";
  private static final String DURATION = "otel.sdk.exporter.operation.duration";
  private static final String NAME = "otel.component.name";
  private static final String HOST = "server.address";
  private static final HttpUrl AGENT_URL = HttpUrl.get("http://agent-host:8126");
  private static final Monitoring MONITORING =
      new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
  private static final CiVisibilityWellKnownTags WELL_KNOWN_TAGS =
      new CiVisibilityWellKnownTags(
          "runtime-id", "env", "java", "runtime", "1", "vendor", "arch", "os", "1", "false");

  @BeforeAll
  static void registerHistogramFactory() {
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  /** A status of 0 stands for a connection failure, which the APIs report as a failed response. */
  // spotless:off
  @TableTest({
      "scenario           | status | errorType                   ",
      "success            | 200    |                             ",
      "client error       | 400    | '400'                       ",
      "server error       | 503    | '503'                       ",
      "connection refused | 0      | 'java.net.ConnectException' "
  })
  // spotless:on
  void eachSendIsRecordedAsAnExport(int status, String errorType) {
    RemoteApi.Response response =
        status == 0
            ? RemoteApi.Response.failed(new ConnectException("refused"))
            : status < 300 ? RemoteApi.Response.success(status) : RemoteApi.Response.failed(status);
    DDAgentApi api = mock(DDAgentApi.class);
    when(api.sendSerializedTraces(any())).thenReturn(response);
    OtelExporterId exporter = exporter();
    String name = exporter.component().name();
    send(api, exporter, 3);

    OtelMetricCollector metrics = collect();
    assertEquals(0L, onlyPoint(metrics, INFLIGHT, NAME, name).value.longValue());
    DataPointEntry exported = onlyPoint(metrics, EXPORTED, NAME, name);
    assertEquals(3L, exported.value.longValue());
    assertEquals("datadog_agent_span_exporter", exported.attrs.get("otel.component.type"));
    assertEquals("agent-host", exported.attrs.get(HOST));
    assertEquals("8126", exported.attrs.get("server.port"));
    assertEquals(errorType, exported.attrs.get("error.type"));
    DataPointEntry duration = onlyPoint(metrics, DURATION, NAME, name);
    assertEquals(
        status == 0 ? null : String.valueOf(status),
        duration.attrs.get("http.response.status_code"));
    assertEquals(1.0, ((OtlpHistogramPoint) duration.point).count);
  }

  @Test
  void aSendThatThrowsIsRecordedAsAFailureAndRethrown() {
    DDAgentApi api = mock(DDAgentApi.class);
    when(api.sendSerializedTraces(any())).thenThrow(new IllegalStateException("boom"));
    OtelExporterId exporter = exporter();
    String name = exporter.component().name();

    assertThrows(IllegalStateException.class, () -> send(api, exporter, 2));

    OtelMetricCollector metrics = collect();
    assertEquals(0L, onlyPoint(metrics, INFLIGHT, NAME, name).value.longValue());
    DataPointEntry exported = onlyPoint(metrics, EXPORTED, NAME, name);
    assertEquals(2L, exported.value.longValue());
    assertEquals("java.lang.IllegalStateException", exported.attrs.get("error.type"));
  }

  @Test
  void theDestinationIsTheApiUrl() {
    DDAgentApi agent =
        new DDAgentApi(
            OkHttpUtils.buildHttpClient(AGENT_URL, 1_000),
            AGENT_URL,
            agentFeatures(),
            MONITORING,
            false);
    DDIntakeApi intake =
        DDIntakeApi.builder()
            .hostUrl(HttpUrl.get("https://intake-host"))
            .apiKey("api-key")
            .trackType(TrackType.CITESTCYCLE)
            .build();

    assertEquals(AGENT_URL, agent.destination());
    assertEquals("intake-host", intake.destination().host());
  }

  /** The agent URL is only a placeholder over a unix domain socket or a named pipe. */
  // spotless:off
  @TableTest({
      "scenario           | socket                    | pipe          ",
      "unix domain socket | '/nonexistent/apm.socket' |               ",
      "named pipe         |                           | 'datadog-apm' "
  })
  // spotless:on
  void localSocketsHaveNoDestination(String socket, String pipe) {
    OkHttpClient client = OkHttpUtils.buildHttpClient(true, socket, pipe, 1_000);
    DDAgentApi api = new DDAgentApi(client, AGENT_URL, agentFeatures(), MONITORING, false);

    assertNull(api.destination());
  }

  /**
   * Each track that sends spans is an exporter of its own; the coverage track is not one. The LLM
   * Observability track counts only the spans it sends.
   */
  @Test
  void intakeTracksAreExportersOfTheirOwn() {
    RemoteApi cycleApi = intakeApi("cycle-intake");
    RemoteApi llmObsApi = intakeApi("llmobs-intake");
    RemoteApi coverageApi = intakeApi("coverage-intake");
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(TrackType.CITESTCYCLE, cycleApi)
            .addTrack(TrackType.LLMOBS, llmObsApi)
            .addTrack(TrackType.CITESTCOV, coverageApi)
            .wellKnownTags(WELL_KNOWN_TAGS)
            .healthMetrics(otelSink())
            .monitoring(MONITORING)
            .flushIntervalMilliseconds(-1)
            .alwaysFlush(false)
            .build();
    writer.start();
    try {
      DDSpan llmObsSpan = buildSpan(0L, DDSpanTypes.LLMOBS, emptyMap());
      DDSpan apmSpan = buildSpan(0L, "web", emptyMap());
      writer.write(asList(llmObsSpan, apmSpan));
      writer.flush();
    } finally {
      writer.close();
    }

    OtelMetricCollector metrics = collect();
    DataPointEntry cycle = onlyPoint(metrics, EXPORTED, HOST, "cycle-intake");
    DataPointEntry llmObs = onlyPoint(metrics, EXPORTED, HOST, "llmobs-intake");
    assertEquals(2L, cycle.value.longValue());
    assertEquals(1L, llmObs.value.longValue());
    for (DataPointEntry point : asList(cycle, llmObs)) {
      assertEquals("datadog_intake_span_exporter", point.attrs.get("otel.component.type"));
    }
    assertNotEquals(cycle.attrs.get(NAME), llmObs.attrs.get(NAME));
    assertTrue(points(metrics, EXPORTED, HOST, "coverage-intake").isEmpty());
  }

  /** Sends one payload of a trace of {@code spans} spans through a dispatcher over {@code api}. */
  private void send(RemoteApi api, OtelExporterId exporter, int spans) {
    HealthMetrics sink = otelSink();
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(agentFeatures()),
            api,
            sink,
            MONITORING,
            ExportRecorder.create(sink, exporter));
    dispatcher.addTrace(nCopies(spans, buildSpan(0L, "web", emptyMap())));
    dispatcher.flush();
  }

  private static OtelExporterId exporter() {
    return new OtelExporterId(
        SPAN, OtelComponentId.next("datadog_agent_span_exporter"), AGENT_URL, false);
  }

  private static DDAgentFeaturesDiscovery agentFeatures() {
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.getTraceEndpoint()).thenReturn("v0.4/traces");
    return features;
  }

  /** A mocked intake API whose destination host is {@code host}, accepting every payload. */
  private static RemoteApi intakeApi(String host) {
    RemoteApi api = mock(RemoteApi.class);
    when(api.destination()).thenReturn(HttpUrl.get("https://" + host + "/api/v2/track"));
    when(api.sendSerializedTraces(any())).thenReturn(RemoteApi.Response.success(200));
    return api;
  }

  private static HealthMetrics otelSink() {
    Config config = mock(Config.class);
    when(config.isOtelSdkSelfMonitoringMetricsActive()).thenReturn(true);
    HealthMetrics sink = OtelSdkSelfMonitoring.healthMetrics(config);
    assertNotNull(sink);
    return sink;
  }

  /** The only point of {@code metric} whose {@code attribute} is {@code value}. */
  private static DataPointEntry onlyPoint(
      OtelMetricCollector metrics, String metric, String attribute, String value) {
    List<DataPointEntry> points = points(metrics, metric, attribute, value);
    assertEquals(1, points.size(), metric + " points with " + attribute + "=" + value);
    return points.get(0);
  }

  private static List<DataPointEntry> points(
      OtelMetricCollector metrics, String metric, String attribute, String value) {
    return metrics.points(metric).stream()
        .filter(p -> value.equals(p.attrs.get(attribute)))
        .collect(toList());
  }
}
