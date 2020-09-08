package datadog.trace.common.writer.ddagent


import datadog.trace.core.DDSpanData
import datadog.trace.core.monitor.Monitor
import datadog.trace.util.test.DDSpecification
import spock.lang.Requires
import spock.lang.Shared

import static datadog.trace.common.writer.ddagent.TraceGenerator.generateRandomTraces

@Requires({ "true" == System.getenv("CI")})
class TraceMapperRealAgentTest extends DDSpecification {

  @Shared
  DDAgentApi v05Api = new DDAgentApi("localhost", 8126, null, 30_000, true)
  @Shared
  DDAgentApi v04Api = new DDAgentApi("localhost", 8126, null, 30_000, false)

  def "send random traces" () {
    setup:
    Monitor monitor = Mock(Monitor)
    PayloadDispatcher dispatcher = new PayloadDispatcher(v05 ? v05Api : v04Api, monitor)
    List<List<DDSpanData>> traces = generateRandomTraces(traceCount, lowCardinality)
    when:
    for (List<DDSpanData> trace : traces) {
      dispatcher.addTrace(trace)
    }
    dispatcher.flush()
    then:
    0 * monitor.onFailedSerialize(_, _)
    0 * monitor.onFailedSend(_, _, _)
    _ * monitor.onSend(_, _, _)
    _ * monitor.onSerialize(_)
    0 * _

    where:
    bufferSize    |   traceCount   | lowCardinality   | v05
    10 << 10      |       0        | true             | true
    10 << 10      |       1        | true             | true
    30 << 10      |       1        | true             | true
    30 << 10      |       2        | true             | true
    10 << 10      |       0        | false            | true
    10 << 10      |       1        | false            | true
    30 << 10      |       1        | false            | true
    30 << 10      |       2        | false            | true
    100 << 10     |       0        | true             | true
    100 << 10     |       1        | true             | true
    100 << 10     |       10       | true             | true
    100 << 10     |       100      | true             | true
    100 << 10     |       0        | false            | true
    100 << 10     |       1        | false            | true
    100 << 10     |       10       | false            | true
    100 << 10     |       100      | false            | true
    10 << 10      |       0        | true             | false
    10 << 10      |       1        | true             | false
    30 << 10      |       1        | true             | false
    30 << 10      |       2        | true             | false
    10 << 10      |       0        | false            | false
    10 << 10      |       1        | false            | false
    30 << 10      |       1        | false            | false
    30 << 10      |       2        | false            | false
    100 << 10     |       0        | true             | false
    100 << 10     |       1        | true             | false
    100 << 10     |       10       | true             | false
    100 << 10     |       100      | true             | false
    100 << 10     |       0        | false            | false
    100 << 10     |       1        | false            | false
    100 << 10     |       10       | false            | false
    100 << 10     |       100      | false            | false
  }
}
