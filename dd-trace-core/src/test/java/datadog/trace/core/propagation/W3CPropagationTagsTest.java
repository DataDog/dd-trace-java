package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.PropagationTags.HeaderType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class W3CPropagationTagsTest extends DDCoreSpecification {

  static Config mockConfig() {
    Config config = mock(Config.class);
    when(config.getxDatadogTagsMaxLength()).thenReturn(512);
    return config;
  }

  static Stream<Arguments> validateTracestateHeaderLimitsArguments() {
    return Stream.of(
        Arguments.of(null, false),
        Arguments.of("", false),
        // check basic key length limit
        Arguments.of(repeat("k", 251) + "0_-*/=1", true),
        Arguments.of(repeat("k", 252) + "0_-*/=1", false),
        // check multi key length limit
        Arguments.of(repeat("t", 241) + "@" + repeat("s", 14) + "=1", true),
        Arguments.of(repeat("t", 242) + "@" + repeat("s", 14) + "=1", false),
        Arguments.of(repeat("t", 241) + "@" + repeat("s", 15) + "=1", false),
        // check value length limit
        Arguments.of("k=" + repeat("v", 256), true),
        Arguments.of("k=" + repeat("v", 257), false),
        // check value length limit with some trailing whitespace
        Arguments.of("k=" + repeat("v", 256) + " \t \t", true),
        Arguments.of("k=" + repeat("v", 257) + " \t \t", false));
  }

  @ParameterizedTest(name = "validate tracestate header limits {0}")
  @MethodSource("validateTracestateHeaderLimitsArguments")
  void validateTracestateHeaderLimits(String headerValue, boolean valid) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    PropagationTags propagationTags =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, headerValue);
    if (valid) {
      assertEquals(
          headerValue == null ? null : headerValue.trim(),
          propagationTags.headerValue(HeaderType.W3C));
    } else {
      assertNull(propagationTags.headerValue(HeaderType.W3C));
    }
    assertEquals(Collections.emptyMap(), propagationTags.createTagMap());
  }

  static Stream<Arguments> validateValidKeyContentsArguments() {
    List<Arguments> args = new ArrayList<>();
    // 'a'..'z'
    for (char c = 'a'; c <= 'z'; c++) {
      args.add(Arguments.of(String.valueOf(c)));
    }
    // '0'..'9'
    for (char c = '0'; c <= '9'; c++) {
      args.add(Arguments.of(String.valueOf(c)));
    }
    return args.stream();
  }

  @ParameterizedTest(name = "validate tracestate header valid key contents ''{0}''")
  @MethodSource("validateValidKeyContentsArguments")
  void validateTracestateHeaderValidKeyContents(String headerChar) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    String lcAlpha = toLcAlpha(headerChar);
    String simpleKeyHeader = lcAlpha + headerChar + "_-*/=1";
    String multiKeyHeader = headerChar + "@" + lcAlpha + headerChar + "_-*/=1";

    PropagationTags simpleKeyPT =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, simpleKeyHeader);
    PropagationTags multiKeyPT =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, multiKeyHeader);

    assertEquals(simpleKeyHeader, simpleKeyPT.headerValue(HeaderType.W3C));
    assertEquals(multiKeyHeader, multiKeyPT.headerValue(HeaderType.W3C));
    assertEquals(Collections.emptyMap(), simpleKeyPT.createTagMap());
    assertEquals(Collections.emptyMap(), multiKeyPT.createTagMap());
  }

  static Stream<Arguments> validateInvalidKeyContentsArguments() {
    // (' '..'ÿ') - (('a'..'z') + ('0'..'9') + '_' + '-' + '*' + '/')
    List<Arguments> args = new ArrayList<>();
    for (int c = ' '; c <= 0xFF; c++) {
      char ch = (char) c;
      if ((ch >= 'a' && ch <= 'z')
          || (ch >= '0' && ch <= '9')
          || ch == '_'
          || ch == '-'
          || ch == '*'
          || ch == '/') {
        continue;
      }
      args.add(Arguments.of(String.valueOf(ch)));
    }
    return args.stream();
  }

  @ParameterizedTest(name = "validate tracestate header invalid key contents ''{0}''")
  @MethodSource("validateInvalidKeyContentsArguments")
  void validateTracestateHeaderInvalidKeyContents(String headerChar) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    String lcAlpha = toLcAlpha(headerChar);
    String simpleKeyHeader = lcAlpha + headerChar + "_-*/=1";
    String multiKeyHeader = lcAlpha + headerChar + "@" + lcAlpha + headerChar + "_-*/=1";

    PropagationTags simpleKeyPT =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, simpleKeyHeader);
    PropagationTags multiKeyPT =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, multiKeyHeader);

    assertNull(simpleKeyPT.headerValue(HeaderType.W3C));
    assertNull(multiKeyPT.headerValue(HeaderType.W3C));
    assertEquals(Collections.emptyMap(), simpleKeyPT.createTagMap());
    assertEquals(Collections.emptyMap(), multiKeyPT.createTagMap());
  }

  static Stream<Arguments> validateValidValueContentsArguments() {
    // (' '..'~') - [',', '=']
    List<Arguments> args = new ArrayList<>();
    for (int c = ' '; c <= '~'; c++) {
      char ch = (char) c;
      if (ch == ',' || ch == '=') {
        continue;
      }
      args.add(Arguments.of(String.valueOf(ch)));
    }
    return args.stream();
  }

  @ParameterizedTest(name = "validate tracestate header valid value contents ''{0}''")
  @MethodSource("validateValidValueContentsArguments")
  void validateTracestateHeaderValidValueContents(String valueChar) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    String lcAlpha = toLcAlpha(valueChar);
    String mostlyOkHeader = lcAlpha + "=" + valueChar;
    String alwaysOkHeader = lcAlpha + "=" + lcAlpha + valueChar + lcAlpha;

    PropagationTags mostlyOkPT =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, mostlyOkHeader);
    PropagationTags alwaysOkPT =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, alwaysOkHeader);

    if (valueChar.equals(" ")) {
      assertNull(mostlyOkPT.headerValue(HeaderType.W3C));
    } else {
      assertEquals(mostlyOkHeader, mostlyOkPT.headerValue(HeaderType.W3C));
    }
    assertEquals(alwaysOkHeader, alwaysOkPT.headerValue(HeaderType.W3C));
    assertEquals(Collections.emptyMap(), mostlyOkPT.createTagMap());
    assertEquals(Collections.emptyMap(), alwaysOkPT.createTagMap());
  }

  static Stream<Arguments> validateInvalidValueContentsArguments() {
    // (' '..'ÿ') - ((' '..'~') - [',', '='])
    // i.e. chars that are NOT in (' '..'~') but are NOT ',' or '='
    // => chars from 0x7F to 0xFF, plus ',' and '='
    List<Arguments> args = new ArrayList<>();
    for (int c = ' '; c <= 0xFF; c++) {
      char ch = (char) c;
      boolean inPrintableAsciiExcludingCommaEquals =
          (c >= ' ' && c <= '~') && ch != ',' && ch != '=';
      if (!inPrintableAsciiExcludingCommaEquals) {
        args.add(Arguments.of(String.valueOf(ch)));
      }
    }
    return args.stream();
  }

  @ParameterizedTest(name = "validate tracestate header invalid value contents ''{0}''")
  @MethodSource("validateInvalidValueContentsArguments")
  void validateTracestateHeaderInvalidValueContents(String valueChar) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    String lcAlpha = toLcAlpha(valueChar);
    String alwaysBadHeader = lcAlpha + "=" + lcAlpha + valueChar + lcAlpha;

    PropagationTags alwaysBadPT =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, alwaysBadHeader);

    assertNull(alwaysBadPT.headerValue(HeaderType.W3C));
    assertEquals(Collections.emptyMap(), alwaysBadPT.createTagMap());
  }

  static Stream<Arguments> validateMemberCountWithoutDdArguments() {
    List<Arguments> args = new ArrayList<>();
    for (int i = 1; i <= 37; i++) {
      args.add(Arguments.of(i));
    }
    return args.stream();
  }

  @ParameterizedTest(
      name = "validate tracestate header number of members {0} without Datadog member")
  @MethodSource("validateMemberCountWithoutDdArguments")
  void validateTracestateHeaderMemberCountWithoutDatadog(int memberCount) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= memberCount; i++) {
      if (i > 1) sb.append(',');
      sb.append("k").append(i).append("=v").append(i);
    }
    String header = sb.toString();

    PropagationTags headerPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, header);

    if (memberCount <= 32) {
      assertEquals(header, headerPT.headerValue(HeaderType.W3C));
    } else {
      assertNull(headerPT.headerValue(HeaderType.W3C));
    }
    assertEquals(Collections.emptyMap(), headerPT.createTagMap());
  }

  static Stream<Arguments> validateMemberCountWithDdArguments() {
    List<Arguments> args = new ArrayList<>();
    for (int i = 1; i <= 37; i++) {
      args.add(Arguments.of(i));
    }
    return args.stream();
  }

  @ParameterizedTest(name = "validate tracestate header number of members {0} with Datadog member")
  @MethodSource("validateMemberCountWithDdArguments")
  void validateTracestateHeaderMemberCountWithDatadog(int memberCount) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    StringBuilder sb = new StringBuilder("dd=s:1");
    for (int i = 1; i <= memberCount; i++) {
      sb.append(',').append("k").append(i).append("=v").append(i);
    }
    String header = sb.toString();

    PropagationTags headerPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, header);

    if (memberCount + 1 <= 32) {
      assertEquals(header, headerPT.headerValue(HeaderType.W3C));
    } else {
      assertNull(headerPT.headerValue(HeaderType.W3C));
    }
    assertEquals(Collections.emptyMap(), headerPT.createTagMap());
  }

  static Stream<Arguments> validateMemberCountWhenPropagatingOriginalArguments() {
    List<Arguments> args = new ArrayList<>();
    for (int i = 1; i <= 37; i++) {
      args.add(Arguments.of(i));
    }
    return args.stream();
  }

  @ParameterizedTest(
      name =
          "validate tracestate header number of members {0} when propagating original tracestate")
  @MethodSource("validateMemberCountWhenPropagatingOriginalArguments")
  void validateTracestateHeaderMemberCountWhenPropagatingOriginal(int memberCount) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= memberCount; i++) {
      if (i > 1) sb.append(',');
      sb.append("k").append(i).append("=v").append(i);
    }
    String header = sb.toString();

    // Build expected header
    String expectedHeader;
    if (memberCount > 32) {
      expectedHeader = "dd=t.dm:-4";
    } else {
      int limit = Math.min(memberCount, 31);
      StringBuilder expSb = new StringBuilder("dd=t.dm:-4");
      for (int i = 1; i <= limit; i++) {
        expSb.append(',').append("k").append(i).append("=v").append(i);
      }
      expectedHeader = expSb.toString();
    }

    PropagationTags datadogHeaderPT =
        propagationTagsFactory.fromHeaderValue(HeaderType.DATADOG, "_dd.p.dm=-4");
    PropagationTags headerPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, header);
    datadogHeaderPT.updateW3CTracestate(headerPT.getW3CTracestate());

    if (memberCount <= 32) {
      assertEquals(expectedHeader, datadogHeaderPT.headerValue(HeaderType.W3C));
    } else {
      assertEquals("dd=t.dm:-4", datadogHeaderPT.headerValue(HeaderType.W3C));
    }
    Map<String, String> expectedTags = new HashMap<>();
    expectedTags.put("_dd.p.dm", "-4");
    assertEquals(expectedTags, datadogHeaderPT.createTagMap());
  }

  static Stream<Arguments> createPropagationTagsFromHeaderValueArguments() {
    return Stream.of(
        Arguments.of(null, null, Collections.emptyMap()),
        Arguments.of("", null, Collections.emptyMap()),
        Arguments.of(
            "dd=s:0;t.dm:934086a686-4",
            "dd=s:0;t.dm:934086a686-4",
            map1("_dd.p.dm", "934086a686-4")),
        Arguments.of("dd=s:0;t.ts:02", "dd=s:0;t.ts:02", map1("_dd.p.ts", "02")),
        Arguments.of("dd=s:0;t.ts:00", "dd=s:0", Collections.emptyMap()),
        Arguments.of(
            "dd=s:0;t.dm:934086a686-4;t.ts:02",
            "dd=s:0;t.dm:934086a686-4;t.ts:02",
            map2("_dd.p.dm", "934086a686-4", "_dd.p.ts", "02")),
        Arguments.of(
            "other=whatever,dd=s:0;t.dm:934086a686-4",
            "dd=s:0;t.dm:934086a686-4,other=whatever",
            map1("_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "dd=s:0;t.dm:934086a687-3,other=whatever",
            "dd=s:0;t.dm:934086a687-3,other=whatever",
            map1("_dd.p.dm", "934086a687-3")),
        Arguments.of(
            "some=thing,dd=s:0;t.dm:934086a687-3,other=whatever",
            "dd=s:0;t.dm:934086a687-3,some=thing,other=whatever",
            map1("_dd.p.dm", "934086a687-3")),
        Arguments.of(
            "some=thing,other=whatever", "some=thing,other=whatever", Collections.emptyMap()),
        Arguments.of(
            "dd=s:0;o:some;t.dm:934086a686-4",
            "dd=s:0;o:some;t.dm:934086a686-4",
            map1("_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "dd=s:0;x:unknown;t.dm:934086a686-4",
            "dd=s:0;t.dm:934086a686-4;x:unknown",
            map1("_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "other=whatever,dd=s:0;x:unknown;t.dm:934086a686-4",
            "dd=s:0;t.dm:934086a686-4;x:unknown,other=whatever",
            map1("_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "other=whatever,dd=xyz:unknown;t.dm:934086a686-4",
            "dd=t.dm:934086a686-4;xyz:unknown,other=whatever",
            map1("_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "other=whatever,dd=t.dm:934086a686-4;xyz:unknown  ",
            "dd=t.dm:934086a686-4;xyz:unknown,other=whatever",
            map1("_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "\tsome=thing \t , dd=s:0;t.dm:934086a687-3\t\t,  other=whatever\t\t ",
            "dd=s:0;t.dm:934086a687-3,some=thing,other=whatever",
            map1("_dd.p.dm", "934086a687-3")),
        Arguments.of(
            "dd=s:0;t.a:b;t.x:y", "dd=s:0;t.a:b;t.x:y", map2("_dd.p.a", "b", "_dd.p.x", "y")),
        Arguments.of(
            "dd=s:0;t.a:b;t.x:y \t", "dd=s:0;t.a:b;t.x:y", map2("_dd.p.a", "b", "_dd.p.x", "y")),
        Arguments.of(
            "dd=s:0;t.a:b ;t.x:y \t", "dd=s:0;t.a:b ;t.x:y", map2("_dd.p.a", "b ", "_dd.p.x", "y")),
        Arguments.of("dd=s:0;t.a:b \t;t.x:y \t", null, Collections.emptyMap()),
        Arguments.of(
            "dd=s:0;t.tid:123456789abcdef0",
            "dd=s:0;t.tid:123456789abcdef0",
            map1("_dd.p.tid", "123456789abcdef0")),
        Arguments.of("dd=t.tid:", null, Collections.emptyMap()),
        Arguments.of(
            "dd=t.tid:" + repeat("1", 1), null, map1("_dd.propagation_error", "malformed_tid 1")),
        Arguments.of(
            "dd=t.tid:" + repeat("1", 15),
            null,
            map1("_dd.propagation_error", "malformed_tid 111111111111111")),
        Arguments.of(
            "dd=t.tid:" + repeat("1", 17),
            null,
            map1("_dd.propagation_error", "malformed_tid 11111111111111111")),
        Arguments.of(
            "dd=t.tid:123456789ABCDEF0",
            null,
            map1("_dd.propagation_error", "malformed_tid 123456789ABCDEF0")),
        Arguments.of(
            "dd=t.tid:123456789abcdefg",
            null,
            map1("_dd.propagation_error", "malformed_tid 123456789abcdefg")),
        Arguments.of(
            "dd=t.tid:-123456789abcdef",
            null,
            map1("_dd.propagation_error", "malformed_tid -123456789abcdef")));
  }

  @ParameterizedTest(name = "create propagation tags from header value {0}")
  @MethodSource("createPropagationTagsFromHeaderValueArguments")
  void createPropagationTagsFromHeaderValue(
      String headerValue, String expectedHeaderValue, Map<String, String> tags) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    PropagationTags propagationTags =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, headerValue);
    assertEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.W3C));
    assertEquals(tags, propagationTags.createTagMap());
  }

  static Stream<Arguments> w3cPropagationTagsShouldTranslateToDatadogTagsArguments() {
    return Stream.of(
        Arguments.of(
            "dd=s:0;t.dm:934086a686-4", "_dd.p.dm=934086a686-4", map1("_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "other=whatever,dd=s:0;t.dm:934086a686-4;t.f:w00t~~",
            "_dd.p.dm=934086a686-4,_dd.p.f=w00t==",
            map2("_dd.p.dm", "934086a686-4", "_dd.p.f", "w00t==")),
        Arguments.of("dd=s:0;t.ts:02", "_dd.p.ts=02", map1("_dd.p.ts", "02")),
        Arguments.of("dd=s:0;t.ts:00", null, Collections.emptyMap()),
        Arguments.of("dd=s:0;t.ts:0", null, Collections.emptyMap()),
        Arguments.of("dd=s:0;t.ts:invalid", null, Collections.emptyMap()),
        Arguments.of(
            "other=whatever,dd=s:0;t.dm:934086a686-4;t.f:w00t~~;t.ts:02",
            "_dd.p.dm=934086a686-4,_dd.p.ts=02,_dd.p.f=w00t==",
            map3("_dd.p.dm", "934086a686-4", "_dd.p.f", "w00t==", "_dd.p.ts", "02")),
        Arguments.of("some=thing,other=whatever", null, Collections.emptyMap()));
  }

  @ParameterizedTest(name = "w3c propagation tags should translate to datadog tags {0}")
  @MethodSource("w3cPropagationTagsShouldTranslateToDatadogTagsArguments")
  void w3cPropagationTagsShouldTranslateToDatadogTags(
      String headerValue, String expectedHeaderValue, Map<String, String> tags) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    PropagationTags propagationTags =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, headerValue);
    assertEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.DATADOG));
    assertEquals(tags, propagationTags.createTagMap());
  }

  static Stream<Arguments> propagationTagsShouldBeUpdatedBySamplingAndOriginArguments() {
    return Stream.of(
        Arguments.of(
            "dd=s:0;o:some;t.dm:934086a686-4",
            (int) PrioritySampling.SAMPLER_KEEP,
            SamplingMechanism.DEFAULT,
            "other",
            "dd=s:0;o:other;t.dm:934086a686-4",
            map1("_dd.p.dm", "934086a686-4")),
        Arguments.of(
            "dd=s:0;o:some;x:unknown",
            (int) PrioritySampling.USER_KEEP,
            SamplingMechanism.LOCAL_USER_RULE,
            "same",
            "dd=s:2;o:same;t.dm:-3;x:unknown",
            map1("_dd.p.dm", "-3")),
        Arguments.of(
            "dd=s:0;o:some;x:unknown",
            (int) PrioritySampling.USER_DROP,
            SamplingMechanism.MANUAL,
            null,
            "dd=s:-1;x:unknown",
            Collections.emptyMap()),
        Arguments.of(
            "dd=s:0;o:some;t.dm:934086a686-4",
            (int) PrioritySampling.SAMPLER_KEEP,
            SamplingMechanism.EXTERNAL_OVERRIDE,
            "other",
            "dd=s:1;o:other;t.dm:-0",
            map1("_dd.p.dm", "-0")),
        Arguments.of(
            "dd=s:1;o:some;t.dm:934086a686-4",
            (int) PrioritySampling.SAMPLER_DROP,
            SamplingMechanism.EXTERNAL_OVERRIDE,
            "other",
            "dd=s:0;o:other",
            Collections.emptyMap()));
  }

  @ParameterizedTest(
      name = "propagation tags should be updated by sampling and origin {0} {1} {2} {3}")
  @MethodSource("propagationTagsShouldBeUpdatedBySamplingAndOriginArguments")
  void propagationTagsShouldBeUpdatedBySamplingAndOrigin(
      String headerValue,
      int priority,
      int mechanism,
      String origin,
      String expectedHeaderValue,
      Map<String, String> tags) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    PropagationTags propagationTags =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, headerValue);

    assertNotEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.W3C));

    propagationTags.updateTraceSamplingPriority(priority, mechanism);
    propagationTags.updateTraceOrigin(origin);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.W3C));
    assertEquals(tags, propagationTags.createTagMap());
  }

  static Stream<Arguments> propagationTagsShouldBeUpdatedByProductTraceSourceArguments() {
    return Stream.of(
        Arguments.of(
            "dd=x:unknown", ProductTraceSource.ASM, "dd=t.ts:02;x:unknown", map1("_dd.p.ts", "02")),
        Arguments.of(
            "dd=t.ts:02;x:unknown",
            ProductTraceSource.DBM,
            "dd=t.ts:12;x:unknown",
            map1("_dd.p.ts", "12")),
        Arguments.of("dd=t.ts:00", ProductTraceSource.ASM, "dd=t.ts:02", map1("_dd.p.ts", "02")),
        Arguments.of(
            "dd=t.ts:FFC00000", ProductTraceSource.ASM, "dd=t.ts:02", map1("_dd.p.ts", "02")));
  }

  @ParameterizedTest(
      name = "propagation tags should be updated by product trace source propagation {1}")
  @MethodSource("propagationTagsShouldBeUpdatedByProductTraceSourceArguments")
  void propagationTagsShouldBeUpdatedByProductTraceSource(
      String headerValue, int product, String expectedHeaderValue, Map<String, String> tags) {
    Config config = mockConfig();
    PropagationTags.Factory propagationTagsFactory = PropagationTags.factory(config);
    PropagationTags propagationTags =
        propagationTagsFactory.fromHeaderValue(HeaderType.W3C, headerValue);

    assertNotEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.W3C));

    propagationTags.addTraceSource(product);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.W3C));
    assertEquals(tags, propagationTags.createTagMap());
  }

  // Helper: convert a char to an lc-alpha char in [a-z]
  private static String toLcAlpha(String cs) {
    char c = cs.charAt(0);
    char a = 'a';
    char z = 'z';
    char result = (char) (a + (Math.abs(c - a) % (z - a)));
    return String.valueOf(result);
  }

  private static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }

  private static Map<String, String> map1(String k1, String v1) {
    Map<String, String> m = new HashMap<>();
    m.put(k1, v1);
    return m;
  }

  private static Map<String, String> map2(String k1, String v1, String k2, String v2) {
    Map<String, String> m = new HashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    return m;
  }

  private static Map<String, String> map3(
      String k1, String v1, String k2, String v2, String k3, String v3) {
    Map<String, String> m = new HashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    m.put(k3, v3);
    return m;
  }
}
