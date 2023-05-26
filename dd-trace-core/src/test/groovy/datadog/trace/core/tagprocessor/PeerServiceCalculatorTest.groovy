package datadog.trace.core.tagprocessor

import datadog.trace.api.DDTags
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.naming.v0.NamingSchemaV0
import datadog.trace.api.naming.v1.NamingSchemaV1
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.DDSpecification
import spock.lang.Ignore

class PeerServiceCalculatorTest extends DDSpecification {
  def "schema v0 : peer service is not calculated by default"() {
    setup:
    def calculator = new PeerServiceCalculator(new NamingSchemaV0().peerService(), Collections.emptyMap())
    when:
    def enrichedTags = calculator.processTags(tags)
    then:
    // tags are not modified
    assert enrichedTags == tags

    where:
    tags                                                 | _
    [:]                                                  | _
    ["peer.hostname": "test"]                            | _
    ["peer.hostname": "test", "db.instance": "instance"] | _
    ["db.instance": "instance", "peer.hostname": "test"] | _
    ["peer.hostname": "test", "rpc.service": "svc"]      | _
    ["rpc.service": "svc", "peer.hostname": "test"]      | _
  }

  def "schema v1: test peer service default logic and precursors"() {
    setup:
    def calculator = new PeerServiceCalculator(new NamingSchemaV1().peerService(), Collections.emptyMap())
    when:
    tags.put(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT)
    def calculated = calculator.processTags(tags)

    then:
    calculated.get(DDTags.PEER_SERVICE_SOURCE) == provenance
    calculated.get(Tags.PEER_SERVICE) == peerService

    where:
    tags                                                                          | provenance         | peerService
    [:]                                                                           | null               | null
    ["peer.hostname": "test"]                                                     | Tags.PEER_HOSTNAME | "test"
    ["peer.hostname": "test"]                                                     | Tags.PEER_HOSTNAME | "test"
    ["peer.hostname": "test", "db.instance": "instance"]                          | Tags.DB_INSTANCE   | "instance"
    ["db.instance": "instance", "peer.hostname": "test"]                          | Tags.DB_INSTANCE   | "instance"
    ["peer.hostname": "test", "rpc.service": "svc", "component": "grpc-client"]   | Tags.RPC_SERVICE   | "svc"
    ["rpc.service": "svc", "peer.hostname": "test", "component": "grpc-client"]   | Tags.RPC_SERVICE   | "svc"
    ["peer.hostname": "test", "peer.service": "userService"]                      | null               | "userService"
  }

  def "schema v0: should calculate defaults if enabled"() {
    setup:
    injectSysConfig(TracerConfig.TRACE_PEER_SERVICE_DEFAULTS_ENABLED, "true")
    def calculator = new PeerServiceCalculator(new NamingSchemaV1().peerService(), Collections.emptyMap())
    when:
    def calculated = calculator.processTags(["span.kind": "client", "peer.hostname": "test"])
    then:
    assert calculated.get(Tags.PEER_SERVICE) == "test"
  }


  def "calculate only for span kind client or producer"() {
    setup:
    def calculator = new PeerServiceCalculator(new NamingSchemaV1().peerService(), Collections.emptyMap())

    when:
    def tags = ["span.kind": kind, "peer.hostname": "test"]

    then:
    assert calculator.processTags(tags).containsKey(Tags.PEER_SERVICE) == calculate

    where:
    kind       | calculate
    "client"   | true
    "producer" | true
    "server"   | false
  }

  @Ignore("https://github.com/DataDog/dd-trace-java/pull/5213")
  def "should apply peer service mappings if configured"() {
    setup:
    injectSysConfig(TracerConfig.TRACE_PEER_SERVICE_MAPPING, "service1:best_service,userService:my_service")
    injectSysConfig(TracerConfig.TRACE_PEER_SERVICE_DEFAULTS_ENABLED, "true")

    def calculator = new PeerServiceCalculator()

    when:
    def calculated = calculator.processTags(tags)

    then:
    assert calculated.get(Tags.PEER_SERVICE) == expected
    assert calculated.get(DDTags.PEER_SERVICE_REMAPPED_FROM) == original

    where:
    tags                                                                    | expected            | original
    ["peer.service": "userService"]                                         | "my_service"        | "userService"
    ["peer.hostname": "test", "span.kind": "client"]                        | "test"              | null
    ["peer.hostname": "service1", "span.kind": "producer"]                  | "best_service"      | "service1"
  }
}
