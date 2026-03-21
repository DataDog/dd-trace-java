import static TraceGenerator.generateRandomTraces;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.OkHttpUtils;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.Config;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class TraceMapperRealAgentTest extends AbstractTraceAgentTest {

  OkHttpClient client;
  MonitoringImpl monitoring;
  DDAgentFeaturesDiscovery v05Discovery;
  DDAgentFeaturesDiscovery v04Discovery;
  DDAgentApi v05Api;
  DDAgentApi v04Api;

  @BeforeEach
  @Override
  void setup() throws Exception {
    super.setup();
    HttpUrl agentUrl = HttpUrl.parse(Config.get().getAgentUrl());
    client = OkHttpUtils.buildHttpClient(agentUrl, 30_000);
    monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
    v05Discovery = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true);
    v04Discovery = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, false, true);
    v05Api = new DDAgentApi(client, agentUrl, v05Discovery, monitoring, false);
    v04Api = new DDAgentApi(client, agentUrl, v04Discovery, monitoring, false);
  }

  // spotless:off
  @TableTest({
    "scenario                                        | traceCount | lowCardinality | v05  ",
    "v05 bufSize 10k 0 traces low cardinality        | 0          | true           | true ",
    "v05 bufSize 10k 1 trace  low cardinality        | 1          | true           | true ",
    "v05 bufSize 30k 1 trace  low cardinality        | 1          | true           | true ",
    "v05 bufSize 30k 2 traces low cardinality        | 2          | true           | true ",
    "v05 bufSize 10k 0 traces high cardinality       | 0          | false          | true ",
    "v05 bufSize 10k 1 trace  high cardinality       | 1          | false          | true ",
    "v05 bufSize 30k 1 trace  high cardinality       | 1          | false          | true ",
    "v05 bufSize 30k 2 traces high cardinality       | 2          | false          | true ",
    "v05 bufSize 100k 0 traces  low cardinality      | 0          | true           | true ",
    "v05 bufSize 100k 1 trace   low cardinality      | 1          | true           | true ",
    "v05 bufSize 100k 10 traces low cardinality      | 10         | true           | true ",
    "v05 bufSize 100k 100 traces low cardinality     | 100        | true           | true ",
    "v05 bufSize 100k 0 traces  high cardinality     | 0          | false          | true ",
    "v05 bufSize 100k 1 trace   high cardinality     | 1          | false          | true ",
    "v05 bufSize 100k 10 traces high cardinality     | 10         | false          | true ",
    "v05 bufSize 100k 100 traces high cardinality    | 100        | false          | true ",
    "v04 bufSize 10k 0 traces low cardinality        | 0          | true           | false",
    "v04 bufSize 10k 1 trace  low cardinality        | 1          | true           | false",
    "v04 bufSize 30k 1 trace  low cardinality        | 1          | true           | false",
    "v04 bufSize 30k 2 traces low cardinality        | 2          | true           | false",
    "v04 bufSize 10k 0 traces high cardinality       | 0          | false          | false",
    "v04 bufSize 10k 1 trace  high cardinality       | 1          | false          | false",
    "v04 bufSize 30k 1 trace  high cardinality       | 1          | false          | false",
    "v04 bufSize 30k 2 traces high cardinality       | 2          | false          | false",
    "v04 bufSize 100k 0 traces  low cardinality      | 0          | true           | false",
    "v04 bufSize 100k 1 trace   low cardinality      | 1          | true           | false",
    "v04 bufSize 100k 10 traces low cardinality      | 10         | true           | false",
    "v04 bufSize 100k 100 traces low cardinality     | 100        | true           | false",
    "v04 bufSize 100k 0 traces  high cardinality     | 0          | false          | false",
    "v04 bufSize 100k 1 trace   high cardinality     | 1          | false          | false",
    "v04 bufSize 100k 10 traces high cardinality     | 10         | false          | false",
    "v04 bufSize 100k 100 traces high cardinality    | 100        | false          | false",
  })
  // spotless:on
  @ParameterizedTest(name = "[{index}] send random traces - {0}")
  void sendRandomTraces(String scenario, int traceCount, boolean lowCardinality, boolean v05) {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    PayloadDispatcherImpl dispatcher =
        new PayloadDispatcherImpl(
            new DDAgentMapperDiscovery(v05 ? v05Discovery : v04Discovery),
            v05 ? v05Api : v04Api,
            healthMetrics,
            monitoring);
    List<List<CoreSpan>> traces = generateRandomTraces(traceCount, lowCardinality);
    for (List<CoreSpan> trace : traces) {
      dispatcher.addTrace(trace);
    }
    dispatcher.flush();

    verify(healthMetrics, never()).onFailedSerialize(any(), any());
    verify(healthMetrics, never()).onFailedSend(anyInt(), anyInt(), any());
  }
}
