package datadog.trace.agent.test.base

import datadog.trace.core.datastreams.StatsGroup

// This class tests multiline multivalue headers for those classes that support it.
abstract class HttpClientTest2 extends HttpClientTest{

  def "test request header #header tag mapping"() {
    when:
    def url = server.address.resolve("/success")
    def status = (value2 == null) ? doRequest(method, url, [[header, value]]) : doRequest(method, url, [[header, value], [header, value2]])
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    then:
    status == 200
    assertTraces(2) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, url, status, false, null, false, tags)
      }
      server.distributedRequestTrace(it, trace(0).last(), tags)
    }
    and:
    if (isDataStreamsEnabled()) {
      StatsGroup first = TEST_DATA_STREAMS_WRITER.groups.find { it.parentHash == 0 }
      verifyAll(first) {
        edgeTags.containsAll(DSM_EDGE_TAGS)
        edgeTags.size() == DSM_EDGE_TAGS.size()
      }
    }

    where:
    method | header                           | value     | value2 | tags
    'GET'  | 'X-Datadog-Test-Both-Header'     | 'foo'     | null   | [ 'both_header_tag': 'foo' ]
    'GET'  | 'X-Datadog-Test-Request-Header'  | 'bar'     | null   | [ 'request_header_tag': 'bar' ]
    'GET'  | 'X-Datadog-Test-Both-Header'     | 'bar,baz' | null   | [ 'both_header_tag': 'bar,baz' ]
    'GET'  | 'X-Datadog-Test-Request-Header'  | 'foo,bar' | null   | [ 'request_header_tag': 'foo,bar' ]
    'GET'  | 'X-Datadog-Test-Both-Header'     | 'bar,baz' | 'foo'  | [ 'both_header_tag': 'bar,baz,foo' ]
    'GET'  | 'X-Datadog-Test-Request-Header'  | 'foo,bar' | 'baz'  | [ 'request_header_tag': 'foo,bar,baz' ]
  }
}
