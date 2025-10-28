package datadog.trace.bootstrap.config.provider

import datadog.trace.test.util.DDSpecification

class PropertiesConfigSourceTest extends DDSpecification {

  def "test null"() {
    when:
    new PropertiesConfigSource(null, true)

    then:
    thrown(AssertionError)
  }

  def "config pulled from properties"() {
    setup:
    def props = new Properties(["abc": "def", "dd.abc": "xyz"])
    def source = new PropertiesConfigSource(props, false)

    expect:
    source.get("abc") == "def"
    source.get("dd.abc") == "xyz"
    source.get("missing") == null
  }

  def "config pulled from properties with prefix"() {
    setup:
    def props = new Properties(["abc": "def", "dd.abc": "xyz"])
    def source = new PropertiesConfigSource(props, true)

    expect:
    source.get("abc") == "xyz"
    source.get("dd.abc") == null
    source.get("missing") == null
  }
}
