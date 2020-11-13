package datadog.trace.common.writer.ddagent

import com.timgroup.statsd.NoOpStatsDClient
import datadog.trace.core.DDSpanData
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.Monitoring
import datadog.trace.test.util.DDSpecification
import spock.lang.Requires
import spock.lang.Shared

import java.util.concurrent.TimeUnit

import static datadog.trace.common.writer.ddagent.TraceGenerator.generateRandomTraces

@Requires({ "true" == System.getenv("CI") })
class TraceMapperRealAgentTest extends DDSpecification {

  @Shared
  Monitoring monitoring = new Monitoring(new NoOpStatsDClient(), 1, TimeUnit.SECONDS)
  @Shared
  DDAgentApi v05Api = new DDAgentApi("http://localhost:8126", null, 30_000, true, false, monitoring)
  @Shared
  DDAgentApi v04Api = new DDAgentApi("http://localhost:8126", null, 30_000, false, false, monitoring)

  def "send random traces"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    PayloadDispatcher dispatcher = new PayloadDispatcher(v05 ? v05Api : v04Api, healthMetrics, monitoring)
    List<List<DDSpanData>> traces = generateRandomTraces(traceCount, lowCardinality)
    when:
    for (List<DDSpanData> trace : traces) {
      dispatcher.addTrace(trace)
    }
    dispatcher.flush()
    then:
    0 * healthMetrics.onFailedSerialize(_, _)
    0 * healthMetrics.onFailedSend(_, _, _)
    _ * healthMetrics.onSend(_, _, _)
    _ * healthMetrics.onSerialize(_)
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
