import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.OkHttpUtils;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.Config;
import datadog.trace.api.ProtocolVersion;
import datadog.trace.common.writer.PayloadDispatcherImpl;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentMapperDiscovery;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.tabletest.junit.TableTest;

class TraceMapperRealAgentTest extends AbstractTraceAgentTest {

  HttpUrl agentUrl;
  OkHttpClient client;
  MonitoringImpl monitoring;

  @BeforeEach
  void setUpClient() {
    agentUrl = HttpUrl.parse(Config.get().getAgentUrl());
    client = OkHttpUtils.buildHttpClient(agentUrl, 30_000);
    monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
  }

  /**
   * The KiB figures in the scenario labels come from the {@code bufferSize} column of the original
   * Groovy data table. That column was never read by the test body, so the sizes distinguish the
   * rows in reports but do not change what is exercised.
   */
  @TableTest({
    "scenario                                    | traceCount | lowCardinality | protocol",
    "V1_0, 10 KiB, no traces, low cardinality    | 0          | true           | V1_0    ",
    "V1_0, 10 KiB, 1 trace, low cardinality      | 1          | true           | V1_0    ",
    "V1_0, 30 KiB, 1 trace, low cardinality      | 1          | true           | V1_0    ",
    "V1_0, 30 KiB, 2 traces, low cardinality     | 2          | true           | V1_0    ",
    "V1_0, 10 KiB, no traces, high cardinality   | 0          | false          | V1_0    ",
    "V1_0, 10 KiB, 1 trace, high cardinality     | 1          | false          | V1_0    ",
    "V1_0, 30 KiB, 1 trace, high cardinality     | 1          | false          | V1_0    ",
    "V1_0, 30 KiB, 2 traces, high cardinality    | 2          | false          | V1_0    ",
    "V1_0, 100 KiB, no traces, low cardinality   | 0          | true           | V1_0    ",
    "V1_0, 100 KiB, 1 trace, low cardinality     | 1          | true           | V1_0    ",
    "V1_0, 100 KiB, 10 traces, low cardinality   | 10         | true           | V1_0    ",
    "V1_0, 100 KiB, 100 traces, low cardinality  | 100        | true           | V1_0    ",
    "V1_0, 100 KiB, no traces, high cardinality  | 0          | false          | V1_0    ",
    "V1_0, 100 KiB, 1 trace, high cardinality    | 1          | false          | V1_0    ",
    "V1_0, 100 KiB, 10 traces, high cardinality  | 10         | false          | V1_0    ",
    "V1_0, 100 KiB, 100 traces, high cardinality | 100        | false          | V1_0    ",
    "V0_5, 10 KiB, no traces, low cardinality    | 0          | true           | V0_5    ",
    "V0_5, 10 KiB, 1 trace, low cardinality      | 1          | true           | V0_5    ",
    "V0_5, 30 KiB, 1 trace, low cardinality      | 1          | true           | V0_5    ",
    "V0_5, 30 KiB, 2 traces, low cardinality     | 2          | true           | V0_5    ",
    "V0_5, 10 KiB, no traces, high cardinality   | 0          | false          | V0_5    ",
    "V0_5, 10 KiB, 1 trace, high cardinality     | 1          | false          | V0_5    ",
    "V0_5, 30 KiB, 1 trace, high cardinality     | 1          | false          | V0_5    ",
    "V0_5, 30 KiB, 2 traces, high cardinality    | 2          | false          | V0_5    ",
    "V0_5, 100 KiB, no traces, low cardinality   | 0          | true           | V0_5    ",
    "V0_5, 100 KiB, 1 trace, low cardinality     | 1          | true           | V0_5    ",
    "V0_5, 100 KiB, 10 traces, low cardinality   | 10         | true           | V0_5    ",
    "V0_5, 100 KiB, 100 traces, low cardinality  | 100        | true           | V0_5    ",
    "V0_5, 100 KiB, no traces, high cardinality  | 0          | false          | V0_5    ",
    "V0_5, 100 KiB, 1 trace, high cardinality    | 1          | false          | V0_5    ",
    "V0_5, 100 KiB, 10 traces, high cardinality  | 10         | false          | V0_5    ",
    "V0_5, 100 KiB, 100 traces, high cardinality | 100        | false          | V0_5    ",
    "V0_4, 10 KiB, no traces, low cardinality    | 0          | true           | V0_4    ",
    "V0_4, 10 KiB, 1 trace, low cardinality      | 1          | true           | V0_4    ",
    "V0_4, 30 KiB, 1 trace, low cardinality      | 1          | true           | V0_4    ",
    "V0_4, 30 KiB, 2 traces, low cardinality     | 2          | true           | V0_4    ",
    "V0_4, 10 KiB, no traces, high cardinality   | 0          | false          | V0_4    ",
    "V0_4, 10 KiB, 1 trace, high cardinality     | 1          | false          | V0_4    ",
    "V0_4, 30 KiB, 1 trace, high cardinality     | 1          | false          | V0_4    ",
    "V0_4, 30 KiB, 2 traces, high cardinality    | 2          | false          | V0_4    ",
    "V0_4, 100 KiB, no traces, low cardinality   | 0          | true           | V0_4    ",
    "V0_4, 100 KiB, 1 trace, low cardinality     | 1          | true           | V0_4    ",
    "V0_4, 100 KiB, 10 traces, low cardinality   | 10         | true           | V0_4    ",
    "V0_4, 100 KiB, 100 traces, low cardinality  | 100        | true           | V0_4    ",
    "V0_4, 100 KiB, no traces, high cardinality  | 0          | false          | V0_4    ",
    "V0_4, 100 KiB, 1 trace, high cardinality    | 1          | false          | V0_4    ",
    "V0_4, 100 KiB, 10 traces, high cardinality  | 10         | false          | V0_4    ",
    "V0_4, 100 KiB, 100 traces, high cardinality | 100        | false          | V0_4    "
  })
  void sendRandomTraces(int traceCount, boolean lowCardinality, ProtocolVersion protocol) {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentFeaturesDiscovery discovery =
        new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, protocol, true, false);
    DDAgentApi api = new DDAgentApi(client, agentUrl, discovery, monitoring, false);
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring);
    List<List<CoreSpan>> traces = TraceGenerator.generateRandomTraces(traceCount, lowCardinality);

    for (List<CoreSpan> trace : traces) {
      dispatcher.addTrace((List<? extends CoreSpan<?>>) (List<?>) trace);
    }
    dispatcher.flush();

    verify(healthMetrics, never()).onFailedSerialize(any(), any());
    verify(healthMetrics, never()).onFailedSend(anyInt(), anyInt(), any());
    verify(healthMetrics, atLeast(0)).onSend(anyInt(), anyInt(), any());
    verify(healthMetrics, atLeast(0)).onSerialize(anyInt());
    verify(healthMetrics, never()).onFailedPublish(anyInt(), anyInt());
    verifyNoMoreInteractions(healthMetrics);
  }
}
