package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.naming.v0.NamingSchemaV0;
import datadog.trace.api.naming.v1.NamingSchemaV1;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class PeerServiceCalculatorTest extends DDJavaSpecification {

  private static LinkedHashMap<String, Object> linkedMap(Object... pairs) {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }

  @TableTest({
    "scenario                    | tags                                                ",
    "empty                       | [:]                                                 ",
    "hostname only               | ['peer.hostname': 'test']                           ",
    "hostname and db instance    | ['peer.hostname': 'test', 'db.instance': 'instance']",
    "db instance before hostname | ['db.instance': 'instance', 'peer.hostname': 'test']",
    "hostname and rpc service    | ['peer.hostname': 'test', 'rpc.service': 'svc']     ",
    "rpc service before hostname | ['rpc.service': 'svc', 'peer.hostname': 'test']     "
  })
  void schemaV0PeerServiceIsNotCalculatedByDefault(Map<String, Object> tags) {
    PeerServiceCalculator calculator =
        new PeerServiceCalculator(new NamingSchemaV0().peerService(), Collections.emptyMap());

    TagMap unsafeTags = TagMap.fromMap(tags);
    calculator.processTags(unsafeTags, null, link -> {});

    // tags are not modified
    assertEquals(tags, unsafeTags);
  }

  @TableTest({
    "scenario                              | tags                                                                        | provenance    | peerService",
    "empty                                 | [:]                                                                         |               |            ",
    "hostname only (1)                     | ['peer.hostname': 'test']                                                   | peer.hostname | test       ",
    "hostname only (2)                     | ['peer.hostname': 'test']                                                   | peer.hostname | test       ",
    "hostname and db instance              | ['peer.hostname': 'test', 'db.instance': 'instance']                        | db.instance   | instance   ",
    "db instance before hostname           | ['db.instance': 'instance', 'peer.hostname': 'test']                        | db.instance   | instance   ",
    "hostname, rpc service, grpc component | ['peer.hostname': 'test', 'rpc.service': 'svc', 'component': 'grpc-client'] | rpc.service   | svc        ",
    "rpc service before hostname           | ['rpc.service': 'svc', 'peer.hostname': 'test', 'component': 'grpc-client'] | rpc.service   | svc        ",
    "hostname and peer service             | ['peer.hostname': 'test', 'peer.service': 'userService']                    |               | userService"
  })
  void schemaV1TestPeerServiceDefaultLogicAndPrecursors(
      Map<String, Object> tags, String provenance, String peerService) {
    PeerServiceCalculator calculator =
        new PeerServiceCalculator(new NamingSchemaV1().peerService(), Collections.emptyMap());

    Map<String, Object> tagsWithSpanKind = new LinkedHashMap<>(tags);
    tagsWithSpanKind.put(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);

    TagMap unsafeTags = TagMap.fromMap(tagsWithSpanKind);
    calculator.processTags(unsafeTags, null, link -> {});

    assertEquals(provenance, unsafeTags.get(DDTags.PEER_SERVICE_SOURCE));
    assertEquals(peerService, unsafeTags.get(Tags.PEER_SERVICE));
  }

  @WithConfig(key = "trace.peer.service.defaults.enabled", value = "true")
  @Test
  void schemaV0ShouldCalculateDefaultsIfEnabled() {
    PeerServiceCalculator calculator =
        new PeerServiceCalculator(new NamingSchemaV0().peerService(), Collections.emptyMap());

    TagMap unsafeTags = TagMap.fromMap(linkedMap("span.kind", "client", "peer.hostname", "test"));
    calculator.processTags(unsafeTags, null, link -> {});

    assertEquals("test", unsafeTags.get(Tags.PEER_SERVICE));
  }

  @TableTest({
    "scenario | kind     | calculate",
    "client   | client   | true     ",
    "producer | producer | true     ",
    "server   | server   | false    "
  })
  void calculateOnlyForSpanKindClientOrProducer(String kind, boolean calculate) {
    PeerServiceCalculator calculator =
        new PeerServiceCalculator(new NamingSchemaV1().peerService(), Collections.emptyMap());

    Map<String, Object> tags = linkedMap("span.kind", kind, "peer.hostname", "test");
    TagMap unsafeTags = TagMap.fromMap(tags);
    calculator.processTags(unsafeTags, null, link -> {});

    assertEquals(calculate, unsafeTags.containsKey(Tags.PEER_SERVICE));
  }

  @WithConfig(
      key = "trace.peer.service.mapping",
      value = "service1:best_service,userService:my_service")
  @WithConfig(key = "trace.peer.service.defaults.enabled", value = "true")
  @TableTest({
    "scenario                         | tags                                                   | expected     | original   ",
    "peer service remapped            | ['peer.service': 'userService']                        | my_service   | userService",
    "hostname client, no remap        | ['peer.hostname': 'test', 'span.kind': 'client']       | test         |            ",
    "hostname producer, remap service | ['peer.hostname': 'service1', 'span.kind': 'producer'] | best_service | service1   "
  })
  void shouldApplyPeerServiceMappingsIfConfigured(
      Map<String, Object> tags, String expected, String original) {
    PeerServiceCalculator calculator =
        new PeerServiceCalculator(
            new NamingSchemaV0().peerService(), Config.get().getPeerServiceMapping());

    TagMap unsafeTags = TagMap.fromMap(tags);
    calculator.processTags(unsafeTags, null, link -> {});

    assertEquals(expected, unsafeTags.get(Tags.PEER_SERVICE));
    assertEquals(original, unsafeTags.get(DDTags.PEER_SERVICE_REMAPPED_FROM));
  }

  @WithConfig(key = "trace.peer.service.component.overrides", value = "java-couchbase:couchbase")
  @WithConfig(key = "trace.peer.service.defaults.enabled", value = "true")
  @TableTest({
    "scenario                    | tags                                                                             | expected  | source             ",
    "component override applies  | ['component': 'java-couchbase', 'span.kind': 'client']                           | couchbase | _component_override",
    "hostname wins over override | ['peer.hostname': 'host1', 'span.kind': 'client', 'component': 'my-http-client'] | host1     | peer.hostname      "
  })
  void shouldOverridePeerServiceValuesIfConfigured(
      Map<String, Object> tags, String expected, String source) {
    PeerServiceCalculator calculator =
        new PeerServiceCalculator(
            new NamingSchemaV0().peerService(), Config.get().getPeerServiceComponentOverrides());

    TagMap unsafeTags = TagMap.fromMap(tags);
    calculator.processTags(unsafeTags, null, link -> {});

    assertEquals(expected, unsafeTags.get(Tags.PEER_SERVICE));
    assertEquals(source, unsafeTags.get(DDTags.PEER_SERVICE_SOURCE));
  }
}
