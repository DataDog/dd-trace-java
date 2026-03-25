import static TraceGenerator.generateRandomTraces
import static datadog.trace.api.ProtocolVersion.V0_4
import static datadog.trace.api.ProtocolVersion.V0_5
import static datadog.trace.api.ProtocolVersion.V1_0

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.http.OkHttpUtils
import datadog.metrics.api.statsd.StatsDClient
import datadog.metrics.impl.MonitoringImpl
import datadog.trace.api.Config
import datadog.trace.common.writer.PayloadDispatcherImpl
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.DDAgentMapperDiscovery
import datadog.trace.core.CoreSpan
import datadog.trace.core.monitor.HealthMetrics
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class TraceMapperRealAgentTest extends AbstractTraceAgentTest {
  HttpUrl agentUrl
  OkHttpClient client
  MonitoringImpl monitoring

  def setup() {
    agentUrl = HttpUrl.parse(Config.get().getAgentUrl())
    client = OkHttpUtils.buildHttpClient(agentUrl, 30_000)
    monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)
  }

  def "send random traces"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentFeaturesDiscovery discovery = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, protocol, true)
    DDAgentApi api = new DDAgentApi(client, agentUrl, discovery, monitoring, false)
    PayloadDispatcherImpl dispatcher = new PayloadDispatcherImpl(new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring)
    List<List<CoreSpan>> traces = generateRandomTraces(traceCount, lowCardinality)
    when:
    for (List<CoreSpan> trace : traces) {
      dispatcher.addTrace(trace)
    }
    dispatcher.flush()
    then:
    0 * healthMetrics.onFailedSerialize(_, _)
    0 * healthMetrics.onFailedSend(_, _, _)
    _ * healthMetrics.onSend(_, _, _)
    _ * healthMetrics.onSerialize(_)
    _ * healthMetrics.onFailedPublish(_)
    0 * _

    where:
    bufferSize | traceCount | lowCardinality | protocol
    10 << 10   | 0          | true           | V1_0
    10 << 10   | 1          | true           | V1_0
    30 << 10   | 1          | true           | V1_0
    30 << 10   | 2          | true           | V1_0
    10 << 10   | 0          | false          | V1_0
    10 << 10   | 1          | false          | V1_0
    30 << 10   | 1          | false          | V1_0
    30 << 10   | 2          | false          | V1_0
    100 << 10  | 0          | true           | V1_0
    100 << 10  | 1          | true           | V1_0
    100 << 10  | 10         | true           | V1_0
    100 << 10  | 100        | true           | V1_0
    100 << 10  | 0          | false          | V1_0
    100 << 10  | 1          | false          | V1_0
    100 << 10  | 10         | false          | V1_0
    100 << 10  | 100        | false          | V1_0
    10 << 10   | 0          | true           | V0_5
    10 << 10   | 1          | true           | V0_5
    30 << 10   | 1          | true           | V0_5
    30 << 10   | 2          | true           | V0_5
    10 << 10   | 0          | false          | V0_5
    10 << 10   | 1          | false          | V0_5
    30 << 10   | 1          | false          | V0_5
    30 << 10   | 2          | false          | V0_5
    100 << 10  | 0          | true           | V0_5
    100 << 10  | 1          | true           | V0_5
    100 << 10  | 10         | true           | V0_5
    100 << 10  | 100        | true           | V0_5
    100 << 10  | 0          | false          | V0_5
    100 << 10  | 1          | false          | V0_5
    100 << 10  | 10         | false          | V0_5
    100 << 10  | 100        | false          | V0_5
    10 << 10   | 0          | true           | V0_4
    10 << 10   | 1          | true           | V0_4
    30 << 10   | 1          | true           | V0_4
    30 << 10   | 2          | true           | V0_4
    10 << 10   | 0          | false          | V0_4
    10 << 10   | 1          | false          | V0_4
    30 << 10   | 1          | false          | V0_4
    30 << 10   | 2          | false          | V0_4
    100 << 10  | 0          | true           | V0_4
    100 << 10  | 1          | true           | V0_4
    100 << 10  | 10         | true           | V0_4
    100 << 10  | 100        | true           | V0_4
    100 << 10  | 0          | false          | V0_4
    100 << 10  | 1          | false          | V0_4
    100 << 10  | 10         | false          | V0_4
    100 << 10  | 100        | false          | V0_4
  }
}
