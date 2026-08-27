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

  @TableTest({
    "scenario | traceCount | lowCardinality | protocol",
    "1        | 0          | true           | V1_0    ",
    "2        | 1          | true           | V1_0    ",
    "3        | 1          | true           | V1_0    ",
    "4        | 2          | true           | V1_0    ",
    "5        | 0          | false          | V1_0    ",
    "6        | 1          | false          | V1_0    ",
    "7        | 1          | false          | V1_0    ",
    "8        | 2          | false          | V1_0    ",
    "9        | 0          | true           | V1_0    ",
    "10       | 1          | true           | V1_0    ",
    "11       | 10         | true           | V1_0    ",
    "12       | 100        | true           | V1_0    ",
    "13       | 0          | false          | V1_0    ",
    "14       | 1          | false          | V1_0    ",
    "15       | 10         | false          | V1_0    ",
    "16       | 100        | false          | V1_0    ",
    "17       | 0          | true           | V0_5    ",
    "18       | 1          | true           | V0_5    ",
    "19       | 1          | true           | V0_5    ",
    "20       | 2          | true           | V0_5    ",
    "21       | 0          | false          | V0_5    ",
    "22       | 1          | false          | V0_5    ",
    "23       | 1          | false          | V0_5    ",
    "24       | 2          | false          | V0_5    ",
    "25       | 0          | true           | V0_5    ",
    "26       | 1          | true           | V0_5    ",
    "27       | 10         | true           | V0_5    ",
    "28       | 100        | true           | V0_5    ",
    "29       | 0          | false          | V0_5    ",
    "30       | 1          | false          | V0_5    ",
    "31       | 10         | false          | V0_5    ",
    "32       | 100        | false          | V0_5    ",
    "33       | 0          | true           | V0_4    ",
    "34       | 1          | true           | V0_4    ",
    "35       | 1          | true           | V0_4    ",
    "36       | 2          | true           | V0_4    ",
    "37       | 0          | false          | V0_4    ",
    "38       | 1          | false          | V0_4    ",
    "39       | 1          | false          | V0_4    ",
    "40       | 2          | false          | V0_4    ",
    "41       | 0          | true           | V0_4    ",
    "42       | 1          | true           | V0_4    ",
    "43       | 10         | true           | V0_4    ",
    "44       | 100        | true           | V0_4    ",
    "45       | 0          | false          | V0_4    ",
    "46       | 1          | false          | V0_4    ",
    "47       | 10         | false          | V0_4    ",
    "48       | 100        | false          | V0_4    "
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
    verify(healthMetrics, atLeast(0)).onFailedPublish(anyInt(), anyInt());
    verifyNoMoreInteractions(healthMetrics);
  }
}
