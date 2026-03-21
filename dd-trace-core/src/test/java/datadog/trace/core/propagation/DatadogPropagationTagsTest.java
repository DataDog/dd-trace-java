package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.APPSEC;
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.api.sampling.SamplingMechanism.UNKNOWN;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import datadog.trace.api.Config;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DatadogPropagationTagsTest extends DDCoreSpecification {

  static PropagationTags.Factory createFactory() {
    Config config = mock(Config.class);
    when(config.getxDatadogTagsMaxLength()).thenReturn(512);
    return PropagationTags.factory(config);
  }

  static Stream<Arguments> createPropagationTagsFromHeaderValueArguments() {
    return Stream.of(
        Arguments.of(null, null, Collections.emptyMap()),
        Arguments.of("", null, Collections.emptyMap()),
        Arguments.of(
            "_dd.p.dm=934086a686-4", "_dd.p.dm=934086a686-4", map("_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "_dd.p.dm=934086a686-10", "_dd.p.dm=934086a686-10", map("_dd.p.dm", "934086a686-10")),
        Arguments.of(
            "_dd.p.dm=934086a686-102",
            "_dd.p.dm=934086a686-102",
            map("_dd.p.dm", "934086a686-102")),
        Arguments.of("_dd.p.dm=-1", "_dd.p.dm=-1", map("_dd.p.dm", "-1")),
        Arguments.of("_dd.p.anytag=value", "_dd.p.anytag=value", map("_dd.p.anytag", "value")),
        // drop _dd.p.upstream_services and any other but _dd.p.*
        Arguments.of("_dd.b.somekey=value", null, Collections.emptyMap()),
        Arguments.of(
            "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1", null, Collections.emptyMap()),
        Arguments.of(
            "_dd.p.dm=934086a686-4,_dd.p.anytag=value",
            "_dd.p.dm=934086a686-4,_dd.p.anytag=value",
            map2("_dd.p.dm", "934086a686-4", "_dd.p.anytag", "value")),
        Arguments.of(
            "_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value",
            "_dd.p.dm=934086a686-4,_dd.p.anytag=value",
            map2("_dd.p.dm", "934086a686-4", "_dd.p.anytag", "value")),
        Arguments.of(
            "_dd.b.keyonly=value,_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value",
            "_dd.p.dm=934086a686-4,_dd.p.anytag=value",
            map2("_dd.p.dm", "934086a686-4", "_dd.p.anytag", "value")),
        // valid tag value containing spaces
        Arguments.of("_dd.p.ab=1 2 3", "_dd.p.ab=1 2 3", map("_dd.p.ab", "1 2 3")),
        Arguments.of("_dd.p.ab= 123 ", "_dd.p.ab= 123 ", map("_dd.p.ab", " 123 ")),
        // decoding error
        Arguments.of("_dd.p.keyonly", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of(",_dd.p.dm=Value", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of(",", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            "_dd.b.somekey=value,_dd.p.dm=934086a686-4,_dd.p.keyonly,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value",
            null,
            map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            "_dd.p.keyonly,_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value",
            null,
            map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            ",_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value",
            null,
            map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            "_dd.p.dm=934086a686-4,,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value",
            null,
            map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            "_dd.p.dm=934086a686-4, ,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value",
            null,
            map("_dd.propagation_error", "decoding_error")),
        // do not validate tag value if the tag is dropped
        Arguments.of(
            "_dd.p.upstream_services=bmV1dHJvbg==|0|1|0.2253", null, Collections.emptyMap()),
        Arguments.of(" _dd.p.ab=123", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p.a b=123", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p.ab =123", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p. ab=123", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p.a=b=1=2", "_dd.p.a=b=1=2", map("_dd.p.a", "b=1=2")),
        Arguments.of("_dd.p.1\u00f62=value", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p.ab=1=2", "_dd.p.ab=1=2", map("_dd.p.ab", "1=2")),
        Arguments.of("_dd.p.ab=1\u00f42", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p.dm=934086A686-4", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p.dm=934086a66-4", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            "_dd.p.dm=934086a6653-4", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p.dm=934086a66534", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p.dm=934086a665-", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p.dm=934086a665-a", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            "_dd.p.dm=934086a665-12b", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p.tid=", null, map("_dd.propagation_error", "decoding_error")),
        Arguments.of("_dd.p.tid=1", null, map("_dd.propagation_error", "malformed_tid 1")),
        Arguments.of(
            "_dd.p.tid=" + repeat("1", 15),
            null,
            map("_dd.propagation_error", "malformed_tid " + repeat("1", 15))),
        Arguments.of(
            "_dd.p.tid=" + repeat("1", 17),
            null,
            map("_dd.propagation_error", "malformed_tid " + repeat("1", 17))),
        Arguments.of(
            "_dd.p.tid=123456789ABCDEF0",
            null,
            map("_dd.propagation_error", "malformed_tid 123456789ABCDEF0")),
        Arguments.of(
            "_dd.p.tid=123456789abcdefg",
            null,
            map("_dd.propagation_error", "malformed_tid 123456789abcdefg")),
        Arguments.of(
            "_dd.p.tid=-123456789abcdef",
            null,
            map("_dd.propagation_error", "malformed_tid -123456789abcdef")),
        Arguments.of("_dd.p.ts=02", "_dd.p.ts=02", map("_dd.p.ts", "02")),
        Arguments.of("_dd.p.ts=00", null, Collections.emptyMap()),
        Arguments.of("_dd.p.ts=foo", null, map("_dd.propagation_error", "decoding_error")));
  }

  @ParameterizedTest
  @MethodSource("createPropagationTagsFromHeaderValueArguments")
  void createPropagationTagsFromHeaderValue(
      String headerValue, String expectedHeaderValue, Map<String, String> tags) {
    PropagationTags.Factory factory = createFactory();
    PropagationTags propagationTags =
        factory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, headerValue);

    assertEquals(
        expectedHeaderValue, propagationTags.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(tags, propagationTags.createTagMap());
  }

  static Stream<Arguments> datadogPropagationTagsShouldTranslateToW3CTagsArguments() {
    return Stream.of(
        Arguments.of(
            "_dd.p.dm=934086a686-4", "dd=t.dm:934086a686-4", map("_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "_dd.p.dm=934086a686-4,_dd.p.f=w00t==",
            "dd=t.dm:934086a686-4;t.f:w00t~~",
            map2("_dd.p.dm", "934086a686-4", "_dd.p.f", "w00t==")),
        Arguments.of(
            "_dd.p.dm=934086a686-4,_dd.p.appsec=1",
            "dd=t.dm:934086a686-4;t.appsec:1",
            map2("_dd.p.dm", "934086a686-4", "_dd.p.appsec", "1")));
  }

  @ParameterizedTest
  @MethodSource("datadogPropagationTagsShouldTranslateToW3CTagsArguments")
  void datadogPropagationTagsShouldTranslateToW3CTags(
      String headerValue, String expectedHeaderValue, Map<String, String> tags) {
    PropagationTags.Factory factory = createFactory();
    PropagationTags propagationTags =
        factory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, headerValue);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(PropagationTags.HeaderType.W3C));
    assertEquals(tags, propagationTags.createTagMap());
  }

  static Stream<Arguments> updatePropagationTagsSamplingMechanismArguments() {
    return Stream.of(
        // keep the existing dm tag as is
        Arguments.of(
            "_dd.p.dm=934086a686-4",
            UNSET,
            UNKNOWN,
            "_dd.p.dm=934086a686-4",
            map("_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "_dd.p.dm=934086a686-3",
            SAMPLER_KEEP,
            AGENT_RATE,
            "_dd.p.dm=934086a686-3",
            map("_dd.p.dm", "934086a686-3")),
        Arguments.of(
            "_dd.p.dm=93485302ab-1",
            SAMPLER_KEEP,
            APPSEC,
            "_dd.p.dm=93485302ab-1",
            map("_dd.p.dm", "93485302ab-1")),
        Arguments.of(
            "_dd.p.dm=934086a686-4,_dd.p.anytag=value",
            SAMPLER_KEEP,
            AGENT_RATE,
            "_dd.p.dm=934086a686-4,_dd.p.anytag=value",
            map2("_dd.p.dm", "934086a686-4", "_dd.p.anytag", "value")),
        Arguments.of(
            "_dd.p.dm=93485302ab-2,_dd.p.anytag=value",
            SAMPLER_KEEP,
            APPSEC,
            "_dd.p.dm=93485302ab-2,_dd.p.anytag=value",
            map2("_dd.p.dm", "93485302ab-2", "_dd.p.anytag", "value")),
        Arguments.of(
            "_dd.p.anytag=value,_dd.p.dm=934086a686-4",
            SAMPLER_KEEP,
            AGENT_RATE,
            "_dd.p.dm=934086a686-4,_dd.p.anytag=value",
            map2("_dd.p.anytag", "value", "_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "_dd.p.anytag=value,_dd.p.dm=93485302ab-2",
            SAMPLER_KEEP,
            APPSEC,
            "_dd.p.dm=93485302ab-2,_dd.p.anytag=value",
            map2("_dd.p.anytag", "value", "_dd.p.dm", "93485302ab-2")),
        Arguments.of(
            "_dd.p.anytag=value,_dd.p.dm=934086a686-4,_dd.p.atag=value",
            SAMPLER_KEEP,
            AGENT_RATE,
            "_dd.p.dm=934086a686-4,_dd.p.anytag=value,_dd.p.atag=value",
            map3("_dd.p.anytag", "value", "_dd.p.dm", "934086a686-4", "_dd.p.atag", "value")),
        Arguments.of(
            "_dd.p.anytag=value,_dd.p.dm=93485302ab-2,_dd.p.atag=value",
            SAMPLER_KEEP,
            APPSEC,
            "_dd.p.dm=93485302ab-2,_dd.p.anytag=value,_dd.p.atag=value",
            map3("_dd.p.anytag", "value", "_dd.p.dm", "93485302ab-2", "_dd.p.atag", "value")),
        Arguments.of(
            "_dd.p.dm=93485302ab-2",
            USER_DROP,
            MANUAL,
            "_dd.p.dm=93485302ab-2",
            map("_dd.p.dm", "93485302ab-2")),
        Arguments.of(
            "_dd.p.anytag=value,_dd.p.dm=93485302ab-2",
            SAMPLER_DROP,
            MANUAL,
            "_dd.p.dm=93485302ab-2,_dd.p.anytag=value",
            map2("_dd.p.anytag", "value", "_dd.p.dm", "93485302ab-2")),
        Arguments.of(
            "_dd.p.dm=93485302ab-2,_dd.p.anytag=value",
            USER_DROP,
            MANUAL,
            "_dd.p.dm=93485302ab-2,_dd.p.anytag=value",
            map2("_dd.p.dm", "93485302ab-2", "_dd.p.anytag", "value")),
        Arguments.of(
            "_dd.p.atag=value,_dd.p.dm=93485302ab-2,_dd.p.anytag=value",
            USER_DROP,
            MANUAL,
            "_dd.p.dm=93485302ab-2,_dd.p.atag=value,_dd.p.anytag=value",
            map3("_dd.p.atag", "value", "_dd.p.dm", "93485302ab-2", "_dd.p.anytag", "value")),
        // propagate sampling mechanism only
        Arguments.of("", SAMPLER_KEEP, DEFAULT, "_dd.p.dm=-0", map("_dd.p.dm", "-0")),
        Arguments.of("", SAMPLER_KEEP, AGENT_RATE, "_dd.p.dm=-1", map("_dd.p.dm", "-1")),
        Arguments.of(
            "_dd.p.anytag=value",
            USER_KEEP,
            MANUAL,
            "_dd.p.dm=-4,_dd.p.anytag=value",
            map2("_dd.p.anytag", "value", "_dd.p.dm", "-4")),
        Arguments.of(
            "_dd.p.anytag=value,_dd.p.atag=value",
            SAMPLER_DROP,
            MANUAL,
            "_dd.p.anytag=value,_dd.p.atag=value",
            map2("_dd.p.anytag", "value", "_dd.p.atag", "value")),
        // do not set the dm tags when mechanism is UNKNOWN
        Arguments.of(
            "_dd.p.anytag=123",
            SAMPLER_KEEP,
            UNKNOWN,
            "_dd.p.anytag=123",
            map("_dd.p.anytag", "123")),
        // invalid input
        Arguments.of(
            ",_dd.p.dm=Value",
            SAMPLER_KEEP,
            AGENT_RATE,
            "_dd.p.dm=-1",
            map2("_dd.propagation_error", "decoding_error", "_dd.p.dm", "-1")));
  }

  @ParameterizedTest
  @MethodSource("updatePropagationTagsSamplingMechanismArguments")
  void updatePropagationTagsSamplingMechanism(
      String originalTagSet,
      int priority,
      int mechanism,
      String expectedHeaderValue,
      Map<String, String> tags) {
    PropagationTags.Factory factory = createFactory();
    PropagationTags propagationTags =
        factory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, originalTagSet);

    propagationTags.updateTraceSamplingPriority(priority, mechanism);

    assertEquals(
        expectedHeaderValue, propagationTags.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(tags, propagationTags.createTagMap());
  }

  static Stream<Arguments> updatePropagationTagsTraceSourcePropagationArguments() {
    return Stream.of(
        Arguments.of("", ProductTraceSource.ASM, "_dd.p.ts=02", map("_dd.p.ts", "02")),
        Arguments.of("_dd.p.ts=00", ProductTraceSource.ASM, "_dd.p.ts=02", map("_dd.p.ts", "02")),
        Arguments.of(
            "_dd.p.ts=FFC00000", ProductTraceSource.ASM, "_dd.p.ts=02", map("_dd.p.ts", "02")),
        Arguments.of("_dd.p.ts=02", ProductTraceSource.DBM, "_dd.p.ts=12", map("_dd.p.ts", "12")),
        // invalid input
        Arguments.of(
            "_dd.p.ts=",
            ProductTraceSource.UNSET,
            null,
            map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            "_dd.p.ts=0",
            ProductTraceSource.UNSET,
            null,
            map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            "_dd.p.ts=0G",
            ProductTraceSource.UNSET,
            null,
            map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            "_dd.p.ts=GG",
            ProductTraceSource.UNSET,
            null,
            map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            "_dd.p.ts=foo",
            ProductTraceSource.UNSET,
            null,
            map("_dd.propagation_error", "decoding_error")),
        Arguments.of(
            "_dd.p.ts=000000002",
            ProductTraceSource.UNSET,
            null,
            map("_dd.propagation_error", "decoding_error")));
  }

  @ParameterizedTest
  @MethodSource("updatePropagationTagsTraceSourcePropagationArguments")
  void updatePropagationTagsTraceSourcePropagation(
      String originalTagSet, int product, String expectedHeaderValue, Map<String, String> tags) {
    PropagationTags.Factory factory = createFactory();
    PropagationTags propagationTags =
        factory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, originalTagSet);

    propagationTags.addTraceSource(product);

    assertEquals(
        expectedHeaderValue, propagationTags.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(tags, propagationTags.createTagMap());
  }

  @Test
  void extractionLimitExceeded() {
    String tags = "_dd.p.anytag=value";
    int limit = tags.length() - 1;
    PropagationTags propagationTags =
        PropagationTags.factory(limit).fromHeaderValue(PropagationTags.HeaderType.DATADOG, tags);

    propagationTags.updateTraceSamplingPriority(USER_KEEP, MANUAL);

    assertEquals("_dd.p.dm=-4", propagationTags.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(
        map2("_dd.propagation_error", "extract_max_size", "_dd.p.dm", "-4"),
        propagationTags.createTagMap());
  }

  @Test
  void injectionLimitExceeded() {
    String tags = "_dd.p.anytag=value";
    int limit = tags.length();
    PropagationTags propagationTags =
        PropagationTags.factory(limit).fromHeaderValue(PropagationTags.HeaderType.DATADOG, tags);

    propagationTags.updateTraceSamplingPriority(USER_KEEP, MANUAL);

    assertNull(propagationTags.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(map("_dd.propagation_error", "inject_max_size"), propagationTags.createTagMap());
  }

  @Test
  void injectionLimitExceededLimit0() {
    PropagationTags propagationTags =
        PropagationTags.factory(0).fromHeaderValue(PropagationTags.HeaderType.DATADOG, "");

    propagationTags.updateTraceSamplingPriority(USER_KEEP, MANUAL);

    assertNull(propagationTags.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(map("_dd.propagation_error", "disabled"), propagationTags.createTagMap());
  }

  // Helper methods

  static Map<String, String> map(String k1, String v1) {
    return Collections.singletonMap(k1, v1);
  }

  static Map<String, String> map2(String k1, String v1, String k2, String v2) {
    Map<String, String> m = new HashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    return m;
  }

  static Map<String, String> map3(
      String k1, String v1, String k2, String v2, String k3, String v3) {
    Map<String, String> m = new HashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    m.put(k3, v3);
    return m;
  }

  static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
