package datadog.trace.bootstrap.instrumentation.decorator.http

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING

class UrlBasedResourceNameCalculatorTest extends DDSpecification {

  def "still uses the simple normalizer by default"() {
    given:
    String method = "GET"
    String path = "/asdf/1234"
    UrlBasedResourceNameCalculator calculator = new UrlBasedResourceNameCalculator()

    when:
    UTF8BytesString resourceName = calculator.calculate(method, path, false)

    then:
    resourceName.toString() == "GET /asdf/?"
  }

  def "uses the ant matching normalizer when configured to"() {
    setup:
    injectSysConfig(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/asdf/*:/test")

    String method = "GET"
    String path = "/asdf/1234"
    UrlBasedResourceNameCalculator calculator = new UrlBasedResourceNameCalculator()

    when:
    UTF8BytesString resourceName = calculator.calculate(method, path, false)

    then:
    resourceName.toString() == "GET /test"
  }

  def "falls back to simple normalizer"() {
    setup:
    injectSysConfig(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/asdf/*:/test")

    String method = "GET"
    String path = "/unknown/1234"
    UrlBasedResourceNameCalculator calculator = new UrlBasedResourceNameCalculator()

    when:
    UTF8BytesString resourceName = calculator.calculate(method, path, false)

    then:
    resourceName.toString() == "GET /unknown/?"
  }

  def "returns sane default when disabled"() {
    setup:
    injectSysConfig(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/a/*:/test")
    injectSysConfig("trace.URLAsResourceNameRule.enabled", "false")

    String method = "GET"
    String path = "/unknown/1234"
    UrlBasedResourceNameCalculator calculator = new UrlBasedResourceNameCalculator()

    when:
    UTF8BytesString resourceName = calculator.calculate(method, path, false)

    then:
    resourceName.toString() == "/"
  }
}
