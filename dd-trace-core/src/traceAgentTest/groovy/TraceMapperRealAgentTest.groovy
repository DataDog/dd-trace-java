import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.http.OkHttpUtils
import datadog.metrics.impl.MonitoringImpl
import datadog.metrics.statsd.StatsDClient
import datadog.trace.api.Config
import datadog.trace.common.writer.PayloadDispatcherImpl
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.DDAgentMapperDiscovery
import datadog.trace.core.CoreSpan
import datadog.trace.core.monitor.HealthMetrics
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

import java.util.concurrent.TimeUnit

import static TraceGenerator.generateRandomTraces

class TraceMapperRealAgentTest extends AbstractTraceAgentTest {

  OkHttpClient client
  MonitoringImpl monitoring
  DDAgentFeaturesDiscovery v05Discovery
  DDAgentFeaturesDiscovery v04Discovery
  DDAgentApi v05Api
  DDAgentApi v04Api

  def setup() {
    def agentUrl = HttpUrl.parse(Config.get().getAgentUrl())

    client = OkHttpUtils.buildHttpClient(agentUrl, 30_000)
    monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)

    v05Discovery = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)
    v04Discovery = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, false, true)
    v05Api = new DDAgentApi(client, agentUrl, v05Discovery, monitoring, false)
    v04Api = new DDAgentApi(client, agentUrl, v04Discovery, monitoring, false)
  }

  def "send random traces"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    PayloadDispatcherImpl dispatcher = new PayloadDispatcherImpl(new DDAgentMapperDiscovery(v05 ? v05Discovery : v04Discovery), v05 ? v05Api : v04Api, healthMetrics, monitoring)
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
    bufferSize | traceCount | lowCardinality | v05
    10 << 10   | 0          | true           | true
    10 << 10   | 1          | true           | true
    30 << 10   | 1          | true           | true
    30 << 10   | 2          | true           | true
    10 << 10   | 0          | false          | true
    10 << 10   | 1          | false          | true
    30 << 10   | 1          | false          | true
    30 << 10   | 2          | false          | true
    100 << 10  | 0          | true           | true
    100 << 10  | 1          | true           | true
    100 << 10  | 10         | true           | true
    100 << 10  | 100        | true           | true
    100 << 10  | 0          | false          | true
    100 << 10  | 1          | false          | true
    100 << 10  | 10         | false          | true
    100 << 10  | 100        | false          | true
    10 << 10   | 0          | true           | false
    10 << 10   | 1          | true           | false
    30 << 10   | 1          | true           | false
    30 << 10   | 2          | true           | false
    10 << 10   | 0          | false          | false
    10 << 10   | 1          | false          | false
    30 << 10   | 1          | false          | false
    30 << 10   | 2          | false          | false
    100 << 10  | 0          | true           | false
    100 << 10  | 1          | true           | false
    100 << 10  | 10         | true           | false
    100 << 10  | 100        | true           | false
    100 << 10  | 0          | false          | false
    100 << 10  | 1          | false          | false
    100 << 10  | 10         | false          | false
    100 << 10  | 100        | false          | false
  }
}
