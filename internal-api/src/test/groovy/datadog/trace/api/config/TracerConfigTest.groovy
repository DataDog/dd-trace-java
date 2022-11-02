package datadog.trace.api.config

import datadog.trace.api.Config
import datadog.trace.api.ConfigDefaults
import datadog.trace.bootstrap.config.provider.ConfigProvider
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.TracerConfig.AGENT_HOST
import static datadog.trace.api.config.TracerConfig.AGENT_PORT_LEGACY
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.HTTP_CLIENT_ERROR_STATUSES
import static datadog.trace.api.config.TracerConfig.HTTP_SERVER_ERROR_STATUSES
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.RESPONSE_HEADER_TAGS
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING
import static datadog.trace.api.config.TracerConfig.SPAN_TAGS
import static datadog.trace.api.ConfigTest.PREFIX
import static datadog.trace.api.ConfigTest.DD_SPAN_TAGS_ENV
import static datadog.trace.api.ConfigTest.DD_HEADER_TAGS_ENV
import static datadog.trace.api.ConfigTest.toBitSet
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PORT


class TracerConfigTest extends DDSpecification {

  private static final DD_SERVICE_MAPPING_ENV = "DD_SERVICE_MAPPING"

  def "verify mapping configs on tracer for #mapString"() {
    setup:
    System.setProperty(PREFIX + HEADER_TAGS + ".legacy.parsing.enabled", "true")
    System.setProperty(PREFIX + SERVICE_MAPPING, mapString)
    System.setProperty(PREFIX + SPAN_TAGS, mapString)
    System.setProperty(PREFIX + HEADER_TAGS, mapString)
    System.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rqh1")
    System.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsh1")
    def props = new Properties()
    props.setProperty(HEADER_TAGS + ".legacy.parsing.enabled", "true")
    props.setProperty(SERVICE_MAPPING, mapString)
    props.setProperty(SPAN_TAGS, mapString)
    props.setProperty(HEADER_TAGS, mapString)
    props.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rqh1")
    props.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsh1")

    when:
    def config = new Config()
    def propConfig = Config.get(props)

    then:
    config.serviceMapping == map
    config.mergedSpanTags == map
    config.requestHeaderTags == map
    config.responseHeaderTags == [:]
    propConfig.serviceMapping == map
    propConfig.mergedSpanTags == map
    propConfig.requestHeaderTags == map
    propConfig.responseHeaderTags == [:]

    where:
    // spotless:off
    mapString                                                     | map
    "a:1, a:2, a:3"                                               | [a: "3"]
    "a:b,c:d,e:"                                                  | [a: "b", c: "d"]
    // space separated
    "a:1  a:2  a:3"                                               | [a: "3"]
    "a:b c:d e:"                                                  | [a: "b", c: "d"]
    // More different string variants:
    "a:"                                                          | [:]
    "a:a;"                                                        | [a: "a;"]
    "a:1, a:2, a:3"                                               | [a: "3"]
    "a:1  a:2  a:3"                                               | [a: "3"]
    "a:b,c:d,e:"                                                  | [a: "b", c: "d"]
    "a:b c:d e:"                                                  | [a: "b", c: "d"]
    "key 1!:va|ue_1,"                                             | ["key 1!": "va|ue_1"]
    "key 1!:va|ue_1 "                                             | ["key 1!": "va|ue_1"]
    " key1 :value1 ,\t key2:  value2"                             | [key1: "value1", key2: "value2"]
    "a:b,c,d"                                                     | [:]
    "a:b,c,d,k:v"                                                 | [:]
    "key1 :value1  \t key2:  value2"                              | [key1: "value1", key2: "value2"]
    "dyno:web.1 dynotype:web buildpackversion:dev appname:******" | ["dyno": "web.1", "dynotype": "web", "buildpackversion": "dev", "appname": "******"]
    "is:val:id"                                                   | [is: "val:id"]
    "a:b,is:val:id,x:y"                                           | [a: "b", is: "val:id", x: "y"]
    "a:b:c:d"                                                     | [a: "b:c:d"]
    // Invalid strings:
    ""                                                            | [:]
    "1"                                                           | [:]
    "a"                                                           | [:]
    "a,1"                                                         | [:]
    "!a"                                                          | [:]
    "    "                                                        | [:]
    ",,,,"                                                        | [:]
    ":,:,:,:,"                                                    | [:]
    ": : : : "                                                    | [:]
    "::::"                                                        | [:]
    // spotless:on
  }

  def "verify mapping header tags on tracer for #mapString"() {
    setup:
    Map<String, String> rqMap = map.clone()
    rqMap.put("rqh1", "http.request.headers.rqh1")
    System.setProperty(PREFIX + HEADER_TAGS, mapString)
    System.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rqh1")
    System.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsh1")
    Map<String, String> rsMap = map.collectEntries { k, v -> [k, v.replace("http.request.headers", "http.response.headers")] }
    rsMap.put("rsh1", "http.response.headers.rsh1")
    def props = new Properties()
    props.setProperty(HEADER_TAGS, mapString)
    props.setProperty(PREFIX + REQUEST_HEADER_TAGS, "rQh1")
    props.setProperty(PREFIX + RESPONSE_HEADER_TAGS, "rsH1")

    when:
    def config = new Config()
    def propConfig = Config.get(props)

    then:
    config.requestHeaderTags == rqMap
    propConfig.requestHeaderTags == rqMap
    config.responseHeaderTags == rsMap
    propConfig.responseHeaderTags == rsMap

    where:
    // spotless:off
    mapString                                                     | map
    "a:one, a:two, a:three"                                       | [a: "three"]
    "a:b,c:d,e:"                                                  | [a: "b", c: "d"]
    // space separated
    "a:one  a:two  a:three"                                       | [a: "three"]
    "a:b c:d e:"                                                  | [a: "b", c: "d"]
    // More different string variants:
    "a:"                                                          | [:]
    "a:a;"                                                        | [a: "a;"]
    "a:one, a:two, a:three"                                       | [a: "three"]
    "a:one  a:two  a:three"                                       | [a: "three"]
    "a:b,c:d,e:"                                                  | [a: "b", c: "d"]
    "a:b c:d e:"                                                  | [a: "b", c: "d"]
    "key=1!:va|ue_1,"                                             | ["key=1!": "va|ue_1"]
    "key=1!:va|ue_1 "                                             | ["key=1!": "va|ue_1"]
    " kEy1 :vaLue1 ,\t keY2:  valUe2"                             | [key1: "vaLue1", key2: "valUe2"]
    "a:b,c,D"                                                     | [a: "b", c: "http.request.headers.c", d: "http.request.headers.d"]
    "a:b,C,d,k:v"                                                 | [a: "b", c: "http.request.headers.c", d: "http.request.headers.d", k: "v"]
    "a b c:d "                                                    | [a: "http.request.headers.a", b: "http.request.headers.b", c: "d"]
    "dyno:web.1 dynotype:web buildpackversion:dev appname:n*****" | ["dyno": "web.1", "dynotype": "web", "buildpackversion": "dev", "appname": "n*****"]
    "A.1,B.1"                                                     | ["a.1": "http.request.headers.a_1", "b.1": "http.request.headers.b_1"]
    "is:val:id"                                                   | [is: "val:id"]
    "a:b,is:val:id,x:y"                                           | [a: "b", is: "val:id", x: "y"]
    "a:b:c:d"                                                     | [a: "b:c:d"]
    // Invalid strings:
    ""                                                            | [:]
    "1"                                                           | [:]
    "a:1"                                                         | [:]
    "a,1"                                                         | [:]
    "!a"                                                          | [:]
    "    "                                                        | [:]
    ",,,,"                                                        | [:]
    ":,:,:,:,"                                                    | [:]
    ": : : : "                                                    | [:]
    "::::"                                                        | [:]
    "kEy1 :value1  \t keY2:  value2"                              | [:]
    // spotless:on
  }

  def "verify integer range configs on tracer"() {
    setup:
    System.setProperty(PREFIX + HTTP_SERVER_ERROR_STATUSES, value)
    System.setProperty(PREFIX + HTTP_CLIENT_ERROR_STATUSES, value)
    def props = new Properties()
    props.setProperty(HTTP_CLIENT_ERROR_STATUSES, value)
    props.setProperty(HTTP_SERVER_ERROR_STATUSES, value)

    when:
    def config = new Config()
    def propConfig = Config.get(props)

    then:
    if (expected) {
      assert config.httpServerErrorStatuses == toBitSet(expected)
      assert config.httpClientErrorStatuses == toBitSet(expected)
      assert propConfig.httpServerErrorStatuses == toBitSet(expected)
      assert propConfig.httpClientErrorStatuses == toBitSet(expected)
    } else {
      assert config.httpServerErrorStatuses == TracerConfig.DEFAULT_HTTP_SERVER_ERROR_STATUSES
      assert config.httpClientErrorStatuses == TracerConfig.DEFAULT_HTTP_CLIENT_ERROR_STATUSES
      assert propConfig.httpServerErrorStatuses == TracerConfig.DEFAULT_HTTP_SERVER_ERROR_STATUSES
      assert propConfig.httpClientErrorStatuses == TracerConfig.DEFAULT_HTTP_CLIENT_ERROR_STATUSES
    }

    where:
    value               | expected // null means default value
    // spotless:off
    "1"                 | null
    "a"                 | null
    ""                  | null
    "1000"              | null
    "100-200-300"       | null
    "500"               | [500]
    "100,999"           | [100, 999]
    "999-888"           | 888..999
    "400-403,405-407"   | [400, 401, 402, 403, 405, 406, 407]
    " 400 - 403 , 405 " | [400, 401, 402, 403, 405]
    // spotless:on
  }

  def "verify null value mapping configs on tracer"() {
    setup:
    environmentVariables.set(DD_SERVICE_MAPPING_ENV, mapString)
    environmentVariables.set(DD_SPAN_TAGS_ENV, mapString)
    environmentVariables.set(DD_HEADER_TAGS_ENV, mapString)

    when:
    def config = new Config()

    then:
    config.serviceMapping == map
    config.mergedSpanTags == map
    config.requestHeaderTags == map

    where:
    mapString | map
    // spotless:off
    null      | [:]
    ""        | [:]
    // spotless:on
  }

  def "get analytics sample rate"() {
    setup:
    environmentVariables.set("DD_FOO_ANALYTICS_SAMPLE_RATE", "0.5")
    environmentVariables.set("DD_BAR_ANALYTICS_SAMPLE_RATE", "0.9")
    // trace prefix form should take precedence over the old non-prefix form
    environmentVariables.set("DD_ALIAS_ENV_ANALYTICS_SAMPLE_RATE", "0.8")
    environmentVariables.set("DD_TRACE_ALIAS_ENV_ANALYTICS_SAMPLE_RATE", "0.4")

    System.setProperty("dd.baz.analytics.sample-rate", "0.7")
    System.setProperty("dd.buzz.analytics.sample-rate", "0.3")
    // trace prefix form should take precedence over the old non-prefix form
    System.setProperty("dd.alias-prop.analytics.sample-rate", "0.1")
    System.setProperty("dd.trace.alias-prop.analytics.sample-rate", "0.2")

    when:
    String[] array = services.toArray(new String[0])
    def value = Config.get().getInstrumentationAnalyticsSampleRate(array)

    then:
    value == expected

    where:
    // spotless:off
    services                | expected
    ["foo"]                 | 0.5f
    ["baz"]                 | 0.7f
    ["doesnotexist"]        | 1.0f
    ["doesnotexist", "foo"] | 0.5f
    ["doesnotexist", "baz"] | 0.7f
    ["foo", "bar"]          | 0.5f
    ["bar", "foo"]          | 0.9f
    ["baz", "buzz"]         | 0.7f
    ["buzz", "baz"]         | 0.3f
    ["foo", "baz"]          | 0.5f
    ["baz", "foo"]          | 0.7f
    ["alias-env", "baz"]    | 0.4f
    ["alias-prop", "foo"]   | 0.2f
    // spotless:on
  }

  def "detect if agent is configured using default values"() {
    setup:
    if (host != null) {
      System.setProperty(PREFIX + AGENT_HOST, host)
    }
    if (socket != null) {
      System.setProperty(PREFIX + AGENT_UNIX_DOMAIN_SOCKET, socket)
    }
    if (port != null) {
      System.setProperty(PREFIX + TRACE_AGENT_PORT, port)
    }
    if (legacyPort != null) {
      System.setProperty(PREFIX + AGENT_PORT_LEGACY, legacyPort)
    }

    when:
    def config = new Config()

    then:
    config.isAgentConfiguredUsingDefault() == configuredUsingDefault

    when:
    Properties properties = new Properties()
    if (propertyHost != null) {
      properties.setProperty(AGENT_HOST, propertyHost)
    }
    if (propertySocket != null) {
      properties.setProperty(AGENT_UNIX_DOMAIN_SOCKET, propertySocket)
    }
    if (propertyPort != null) {
      properties.setProperty(TRACE_AGENT_PORT, propertyPort)
    }

    def childConfig = new Config(ConfigProvider.withPropertiesOverride(properties))

    then:
    childConfig.isAgentConfiguredUsingDefault() == childConfiguredUsingDefault

    where:
    // spotless:off
    host                              | socket    | port | legacyPort | propertyHost | propertySocket | propertyPort | configuredUsingDefault | childConfiguredUsingDefault
    null                              | null      | null | null       | null         | null           | null         | true                   | true
    "example"                         | null      | null | null       | null         | null           | null         | false                  | false
    ConfigDefaults.DEFAULT_AGENT_HOST | null | null | null | null | null | null | false | false
    null                              | "example" | null | null       | null         | null           | null         | false                  | false
    null                              | null      | "1"  | null       | null         | null           | null         | false                  | false
    null                              | null      | null | "1"        | null         | null           | null         | false                  | false
    "example"                         | "example" | null | null       | null         | null           | null         | false                  | false
    null                              | null      | null | null       | "example"    | null           | null         | true                   | false
    null                              | null      | null | null       | null         | "example"      | null         | true                   | false
    null                              | null      | null | null       | null         | null           | "1"          | true                   | false
    "example"                         | "example" | null | null       | "example"    | null           | null         | false                  | false
    // spotless:on
  }
}
