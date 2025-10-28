package datadog.trace.instrumentation.selenium

import datadog.trace.agent.test.InstrumentationSpecification

class SeleniumUtilsTest extends InstrumentationSpecification {

  def "test IPv4 address detection: #host"() {
    expect:
    SeleniumUtils.isIPV4Address(host) == expectedValue

    where:
    host                     | expectedValue
    null                     | false
    ""                       | false
    "localhost"              | false
    "google.com"             | false
    "sub.domain.website.com" | false
    "1.2.3"                  | false
    "1.2.3.4.5"              | false
    "192.168.0.abc"          | false
    "192.168.0.999"          | false
    "192.168.0.1"            | true
    "255.255.255.255"        | true
  }

  def "test cookie domain extraction: #host"() {
    expect:
    SeleniumUtils.getCookieDomain(host) == expectedValue

    where:
    host                                                  | expectedValue
    "http://192.168.0.1"                                  | null
    "http://localhost:8080"                               | null
    "http://website.com"                                  | null
    "http://website.com:8080/path?param=value"            | null
    "http://domain.website.com"                           | "website.com"
    "http://sub.domain.website.com:8080/path?param=value" | "website.com"
  }
}
