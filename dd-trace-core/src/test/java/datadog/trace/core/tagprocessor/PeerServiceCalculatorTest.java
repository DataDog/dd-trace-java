package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.naming.v0.NamingSchemaV0;
import datadog.trace.api.naming.v1.NamingSchemaV1;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class PeerServiceCalculatorTest extends DDCoreSpecification {

  @TableTest({
    "scenario                    | tags                                   ",
    "empty tags                  | empty                                  ",
    "peer.hostname only          | peer.hostname=test                     ",
    "peer.hostname + db.instance | peer.hostname=test,db.instance=instance",
    "db.instance + peer.hostname | db.instance=instance,peer.hostname=test",
    "peer.hostname + rpc.service | peer.hostname=test,rpc.service=svc     ",
    "rpc.service + peer.hostname | rpc.service=svc,peer.hostname=test     "
  })
  @ParameterizedTest(name = "{0}")
  void schemaV0PeerServiceIsNotCalculatedByDefault(String scenario, String tagsSpec) {
    PeerServiceCalculator calculator =
        new PeerServiceCalculator(
            new NamingSchemaV0().peerService(), Collections.<String, String>emptyMap());

    Map<String, Object> tags = parseTags(tagsSpec);
    Map<String, Object> enrichedTags =
        calculator.processTags(
            tags,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals(tags, enrichedTags);
  }

  @TableTest({
    "scenario                                  | tagsSpec                                                 | provenance    | peerService",
    "empty                                     | empty                                                    | null          | null       ",
    "peer.hostname only 1                      | peer.hostname=test                                       | peer.hostname | test       ",
    "peer.hostname only 2                      | peer.hostname=test                                       | peer.hostname | test       ",
    "peer.hostname + db.instance               | peer.hostname=test,db.instance=instance                  | db.instance   | instance   ",
    "db.instance + peer.hostname               | db.instance=instance,peer.hostname=test                  | db.instance   | instance   ",
    "peer.hostname + rpc.service + grpc-client | peer.hostname=test,rpc.service=svc,component=grpc-client | rpc.service   | svc        ",
    "rpc.service + peer.hostname + grpc-client | rpc.service=svc,peer.hostname=test,component=grpc-client | rpc.service   | svc        ",
    "peer.hostname + peer.service userService  | peer.hostname=test,peer.service=userService              | null          | userService"
  })
  @ParameterizedTest(name = "{0}")
  void schemaV1TestPeerServiceDefaultLogicAndPrecursors(
      String scenario, String tagsSpec, String provenance, String peerService) {
    PeerServiceCalculator calculator =
        new PeerServiceCalculator(
            new NamingSchemaV1().peerService(), Collections.<String, String>emptyMap());

    Map<String, Object> tags = parseTags(tagsSpec);
    tags.put(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    Map<String, Object> calculated =
        calculator.processTags(
            tags,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    String resolvedProvenance = "null".equals(provenance) ? null : provenance;
    String resolvedPeerService = "null".equals(peerService) ? null : peerService;
    assertEquals(resolvedProvenance, calculated.get(DDTags.PEER_SERVICE_SOURCE));
    assertEquals(resolvedPeerService, calculated.get(Tags.PEER_SERVICE));
  }

  @Test
  void schemaV0ShouldCalculateDefaultsIfEnabled() {
    injectSysConfig(TracerConfig.TRACE_PEER_SERVICE_DEFAULTS_ENABLED, "true");
    PeerServiceCalculator calculator =
        new PeerServiceCalculator(
            new NamingSchemaV0().peerService(), Collections.<String, String>emptyMap());

    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put("span.kind", "client");
    tags.put("peer.hostname", "test");
    Map<String, Object> calculated =
        calculator.processTags(
            tags,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals("test", calculated.get(Tags.PEER_SERVICE));
  }

  @TableTest({
    "scenario | kind     | calculate",
    "client   | client   | true     ",
    "producer | producer | true     ",
    "server   | server   | false    "
  })
  @ParameterizedTest(name = "{0}")
  void calculateOnlyForSpanKindClientOrProducer(String scenario, String kind, boolean calculate) {
    PeerServiceCalculator calculator =
        new PeerServiceCalculator(
            new NamingSchemaV1().peerService(), Collections.<String, String>emptyMap());

    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put("span.kind", kind);
    tags.put("peer.hostname", "test");

    assertEquals(
        calculate,
        calculator
            .processTags(
                tags,
                null,
                Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList())
            .containsKey(Tags.PEER_SERVICE));
  }

  @TableTest({
    "scenario                               | tagsSpec                                  | expected     | original   ",
    "peer.service userService -> my_service | peer.service=userService                  | my_service   | userService",
    "peer.hostname test no remap            | peer.hostname=test,span.kind=client       | test         | null       ",
    "peer.hostname service1 -> best_service | peer.hostname=service1,span.kind=producer | best_service | service1   "
  })
  @ParameterizedTest(name = "{0}")
  void shouldApplyPeerServiceMappingsIfConfigured(
      String scenario, String tagsSpec, String expected, String original) {
    injectSysConfig(
        TracerConfig.TRACE_PEER_SERVICE_MAPPING, "service1:best_service,userService:my_service");
    injectSysConfig(TracerConfig.TRACE_PEER_SERVICE_DEFAULTS_ENABLED, "true");

    PeerServiceCalculator calculator =
        new PeerServiceCalculator(
            new NamingSchemaV0().peerService(), Config.get().getPeerServiceMapping());

    Map<String, Object> tags = parseTags(tagsSpec);
    Map<String, Object> calculated =
        calculator.processTags(
            tags,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals(expected, calculated.get(Tags.PEER_SERVICE));
    String resolvedOriginal = "null".equals(original) ? null : original;
    assertEquals(resolvedOriginal, calculated.get(DDTags.PEER_SERVICE_REMAPPED_FROM));
  }

  @TableTest({
    "scenario                                    | tagsSpec                                                      | expected  | source             ",
    "java-couchbase component override           | component=java-couchbase,span.kind=client                     | couchbase | _component_override",
    "peer.hostname with non-overridden component | peer.hostname=host1,span.kind=client,component=my-http-client | host1     | peer.hostname      "
  })
  @ParameterizedTest(name = "{0}")
  void shouldOverridePeerServiceValuesIfConfigured(
      String scenario, String tagsSpec, String expected, String source) {
    injectSysConfig(
        TracerConfig.TRACE_PEER_SERVICE_COMPONENT_OVERRIDES, "java-couchbase:couchbase");
    injectSysConfig(TracerConfig.TRACE_PEER_SERVICE_DEFAULTS_ENABLED, "true");

    PeerServiceCalculator calculator =
        new PeerServiceCalculator(
            new NamingSchemaV0().peerService(), Config.get().getPeerServiceComponentOverrides());

    Map<String, Object> tags = parseTags(tagsSpec);
    Map<String, Object> calculated =
        calculator.processTags(
            tags,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals(expected, calculated.get(Tags.PEER_SERVICE));
    assertEquals(source, calculated.get(DDTags.PEER_SERVICE_SOURCE));
  }

  /** Parses a simple "key=value,key2=value2" tag spec or "empty" for an empty map. */
  private static Map<String, Object> parseTags(String spec) {
    Map<String, Object> tags = new LinkedHashMap<>();
    if ("empty".equals(spec)) {
      return tags;
    }
    for (String entry : spec.split(",")) {
      String[] kv = entry.split("=", 2);
      tags.put(kv[0].trim(), kv[1].trim());
    }
    return tags;
  }
}
