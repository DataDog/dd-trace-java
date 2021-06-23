package datadog.trace.bootstrap.instrumentation.api

import datadog.trace.test.util.DDSpecification

class URIDataAdapterTest extends DDSpecification {

  def "test URI parts"() {
    setup:
    def uri = new URI(input)
    def adapter = new DefaultURIDataAdapter(uri)

    expect:
    adapter.scheme() == scheme
    adapter.host() == host
    adapter.port() == port
    adapter.path() == path
    adapter.fragment() == fragment
    adapter.query() == query

    where:
    input                                | scheme  |  host  | port | path    | fragment   | query
    "http://host:17/path?query#fragment" | "http"  | "host" | 17   | "/path" | "fragment" | "query"
    "https://h0st"                       | "https" | "h0st" | -1   | ""      | null       | null
    "http://host/v%C3%A4g?fr%C3%A5ga"    | "http"  | "host" | -1   | "/väg"  | null       | "fråga"
  }
}
