package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.PropagationTags.HeaderType;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.junit.utils.tabletest.PrioritySamplingConverter;
import datadog.trace.junit.utils.tabletest.ProductTraceSourceConverter;
import datadog.trace.junit.utils.tabletest.SamplingMechanismConverter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class W3CPropagationTagsTest extends DDCoreJavaSpecification {

  private static final int DD_TAGS_MAX_LEN = 512;

  private static PropagationTags.Factory factory() {
    return PropagationTags.factory(DD_TAGS_MAX_LEN);
  }

  @ParameterizedTest
  @MethodSource("validateTracestateHeaderLimitsArguments")
  void validateTracestateHeaderLimits(String headerValue, boolean valid) {
    PropagationTags propagationTags = factory().fromHeaderValue(HeaderType.W3C, headerValue);

    if (valid) {
      assertEquals(headerValue.trim(), propagationTags.headerValue(HeaderType.W3C));
    } else {
      assertNull(propagationTags.headerValue(HeaderType.W3C));
    }
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), propagationTags.createTagMap());
  }

  static Stream<Arguments> validateTracestateHeaderLimitsArguments() {
    return Stream.of(
        arguments(null, false),
        arguments("", false),
        // check basic key length limit
        arguments(repeat("k", 251) + "0_-*/=1", true),
        arguments(repeat("k", 252) + "0_-*/=1", false),
        // check multi key length limit
        arguments(repeat("t", 241) + "@" + repeat("s", 14) + "=1", true),
        arguments(repeat("t", 242) + "@" + repeat("s", 14) + "=1", false),
        arguments(repeat("t", 241) + "@" + repeat("s", 15) + "=1", false),
        // check value length limit
        arguments("k=" + repeat("v", 256), true),
        arguments("k=" + repeat("v", 257), false),
        // check value length limit with some trailing whitespace
        arguments("k=" + repeat("v", 256) + " \t \t", true),
        arguments("k=" + repeat("v", 257) + " \t \t", false));
  }

  @ParameterizedTest
  @MethodSource("validateTracestateHeaderValidKeyContentsArguments")
  void validateTracestateHeaderValidKeyContents(String headerChar) {
    String lcAlpha = toLcAlpha(headerChar);
    String simpleKeyHeader = lcAlpha + headerChar + "_-*/=1";
    String multiKeyHeader = headerChar + "@" + lcAlpha + headerChar + "_-*/=1";

    PropagationTags simpleKeyPT = factory().fromHeaderValue(HeaderType.W3C, simpleKeyHeader);
    PropagationTags multiKeyPT = factory().fromHeaderValue(HeaderType.W3C, multiKeyHeader);

    assertEquals(simpleKeyHeader, simpleKeyPT.headerValue(HeaderType.W3C));
    assertEquals(multiKeyHeader, multiKeyPT.headerValue(HeaderType.W3C));
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), simpleKeyPT.createTagMap());
    assertEquals(emptyMap(), multiKeyPT.createTagMap());
  }

  static Stream<Arguments> validateTracestateHeaderValidKeyContentsArguments() {
    return IntStream.concat(IntStream.rangeClosed('a', 'z'), IntStream.rangeClosed('0', '9'))
        .mapToObj(c -> arguments(String.valueOf((char) c)));
  }

  @ParameterizedTest
  @MethodSource("validateTracestateHeaderInvalidKeyContentsArguments")
  void validateTracestateHeaderInvalidKeyContents(String headerChar) {
    String lcAlpha = toLcAlpha(headerChar);
    String simpleKeyHeader = lcAlpha + headerChar + "_-*/=1";
    String multiKeyHeader = lcAlpha + headerChar + "@" + lcAlpha + headerChar + "_-*/=1";

    PropagationTags simpleKeyPT = factory().fromHeaderValue(HeaderType.W3C, simpleKeyHeader);
    PropagationTags multiKeyPT = factory().fromHeaderValue(HeaderType.W3C, multiKeyHeader);

    assertNull(simpleKeyPT.headerValue(HeaderType.W3C));
    assertNull(multiKeyPT.headerValue(HeaderType.W3C));
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), simpleKeyPT.createTagMap());
    assertEquals(emptyMap(), multiKeyPT.createTagMap());
  }

  static Stream<Arguments> validateTracestateHeaderInvalidKeyContentsArguments() {
    Set<Character> validKeyChars = new HashSet<>();
    for (char c = 'a'; c <= 'z'; c++) validKeyChars.add(c);
    for (char c = '0'; c <= '9'; c++) validKeyChars.add(c);
    validKeyChars.add('_');
    validKeyChars.add('-');
    validKeyChars.add('*');
    validKeyChars.add('/');
    Stream.Builder<Arguments> builder = Stream.builder();
    for (char c = ' '; c <= 'ÿ'; c++) {
      if (!validKeyChars.contains(c)) {
        builder.add(arguments(String.valueOf(c)));
      }
    }
    return builder.build();
  }

  @ParameterizedTest
  @MethodSource("validateTracestateHeaderValidValueContentsArguments")
  void validateTracestateHeaderValidValueContents(String valueChar) {
    String lcAlpha = toLcAlpha(valueChar);
    String mostlyOkHeader = lcAlpha + "=" + valueChar;
    String alwaysOkHeader = lcAlpha + "=" + lcAlpha + valueChar + lcAlpha;

    PropagationTags mostlyOkPT = factory().fromHeaderValue(HeaderType.W3C, mostlyOkHeader);
    PropagationTags alwaysOkPT = factory().fromHeaderValue(HeaderType.W3C, alwaysOkHeader);

    if (" ".equals(valueChar)) {
      assertNull(mostlyOkPT.headerValue(HeaderType.W3C));
    } else {
      assertEquals(mostlyOkHeader, mostlyOkPT.headerValue(HeaderType.W3C));
    }
    assertEquals(alwaysOkHeader, alwaysOkPT.headerValue(HeaderType.W3C));
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), mostlyOkPT.createTagMap());
    assertEquals(emptyMap(), alwaysOkPT.createTagMap());
  }

  static Stream<Arguments> validateTracestateHeaderValidValueContentsArguments() {
    Stream.Builder<Arguments> builder = Stream.builder();
    for (char c = ' '; c <= '~'; c++) {
      if (c != ',' && c != '=') {
        builder.add(arguments(String.valueOf(c)));
      }
    }
    return builder.build();
  }

  @ParameterizedTest
  @MethodSource("validateTracestateHeaderInvalidValueContentsArguments")
  void validateTracestateHeaderInvalidValueContents(String valueChar) {
    String lcAlpha = toLcAlpha(valueChar);
    String alwaysBadHeader = lcAlpha + "=" + lcAlpha + valueChar + lcAlpha;

    PropagationTags alwaysBadPT = factory().fromHeaderValue(HeaderType.W3C, alwaysBadHeader);

    assertNull(alwaysBadPT.headerValue(HeaderType.W3C));
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), alwaysBadPT.createTagMap());
  }

  static Stream<Arguments> validateTracestateHeaderInvalidValueContentsArguments() {
    Set<Character> validValueChars = new HashSet<>();
    for (char c = ' '; c <= '~'; c++) {
      if (c != ',' && c != '=') validValueChars.add(c);
    }
    Stream.Builder<Arguments> builder = Stream.builder();
    for (char c = ' '; c <= 'ÿ'; c++) {
      if (!validValueChars.contains(c)) {
        builder.add(arguments(String.valueOf(c)));
      }
    }
    return builder.build();
  }

  @ParameterizedTest(name = "{0} members")
  @MethodSource("memberCountArguments")
  void validateTracestateHeaderNumberOfMembersWithoutDatadogMember(int memberCount) {
    String header = buildHeader(memberCount);

    PropagationTags headerPT = factory().fromHeaderValue(HeaderType.W3C, header);

    if (memberCount <= 32) {
      assertEquals(header, headerPT.headerValue(HeaderType.W3C));
    } else {
      assertNull(headerPT.headerValue(HeaderType.W3C));
    }
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), headerPT.createTagMap());
  }

  @ParameterizedTest(name = "{0} members")
  @MethodSource("memberCountArguments")
  void validateTracestateHeaderNumberOfMembersWithDatadogMember(int memberCount) {
    String header = "dd=s:1," + buildHeader(memberCount);

    PropagationTags headerPT = factory().fromHeaderValue(HeaderType.W3C, header);

    if (memberCount + 1 <= 32) {
      assertEquals(header, headerPT.headerValue(HeaderType.W3C));
    } else {
      assertNull(headerPT.headerValue(HeaderType.W3C));
    }
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), headerPT.createTagMap());
  }

  @ParameterizedTest(name = "{0} members")
  @MethodSource("memberCountArguments")
  void validateTracestateHeaderNumberOfMembersWhenPropagatingOriginalTracestate(int memberCount) {
    String header = buildHeader(memberCount);
    String expectedHeader =
        "dd=t.dm:-4," + (memberCount > 32 ? "" : buildHeader(Math.min(memberCount, 31)));

    PropagationTags datadogHeaderPT = factory().fromHeaderValue(HeaderType.DATADOG, "_dd.p.dm=-4");
    PropagationTags headerPT = factory().fromHeaderValue(HeaderType.W3C, header);
    datadogHeaderPT.updateW3CTracestate(headerPT.getW3CTracestate());

    if (memberCount <= 32) {
      assertEquals(expectedHeader, datadogHeaderPT.headerValue(HeaderType.W3C));
    } else {
      assertEquals("dd=t.dm:-4", datadogHeaderPT.headerValue(HeaderType.W3C));
    }
    Map<String, String> expectedTags = new java.util.LinkedHashMap<>();
    expectedTags.put("_dd.p.dm", "-4");
    assertEquals(expectedTags, datadogHeaderPT.createTagMap());
  }

  static IntStream memberCountArguments() {
    return IntStream.rangeClosed(1, 37);
  }

  @TableTest({
    "scenario                                | headerValue                                                            | expectedHeaderValue                                  | tags                                                        ",
    "null                                    |                                                                        |                                                      | [:]                                                         ",
    "empty                                   | ''                                                                     |                                                      | [:]                                                         ",
    "dd only with dm                         | 'dd=s:0;t.dm:934086a686-4'                                             | 'dd=s:0;t.dm:934086a686-4'                           | [_dd.p.dm: 934086a686-4]                                    ",
    "dd only with ts                         | 'dd=s:0;t.ts:02'                                                       | 'dd=s:0;t.ts:02'                                     | [_dd.p.ts: 02]                                              ",
    "dd only with ts zero                    | 'dd=s:0;t.ts:00'                                                       | 'dd=s:0'                                             | [:]                                                         ",
    "dd only with dm and ts                  | 'dd=s:0;t.dm:934086a686-4;t.ts:02'                                     | 'dd=s:0;t.dm:934086a686-4;t.ts:02'                   | [_dd.p.dm: 934086a686-4, _dd.p.ts: 02]                      ",
    "other before dd                         | 'other=whatever,dd=s:0;t.dm:934086a686-4'                              | 'dd=s:0;t.dm:934086a686-4,other=whatever'            | [_dd.p.dm: 934086a686-4]                                    ",
    "dd before other                         | 'dd=s:0;t.dm:934086a687-3,other=whatever'                              | 'dd=s:0;t.dm:934086a687-3,other=whatever'            | [_dd.p.dm: 934086a687-3]                                    ",
    "some before dd before other             | 'some=thing,dd=s:0;t.dm:934086a687-3,other=whatever'                   | 'dd=s:0;t.dm:934086a687-3,some=thing,other=whatever' | [_dd.p.dm: 934086a687-3]                                    ",
    "no dd                                   | 'some=thing,other=whatever'                                            | 'some=thing,other=whatever'                          | [:]                                                         ",
    "dd with origin and dm                   | 'dd=s:0;o:some;t.dm:934086a686-4'                                      | 'dd=s:0;o:some;t.dm:934086a686-4'                    | [_dd.p.dm: 934086a686-4]                                    ",
    "dd with unknown key                     | 'dd=s:0;x:unknown;t.dm:934086a686-4'                                   | 'dd=s:0;t.dm:934086a686-4;x:unknown'                 | [_dd.p.dm: 934086a686-4]                                    ",
    "other before dd with unknown            | 'other=whatever,dd=s:0;x:unknown;t.dm:934086a686-4'                    | 'dd=s:0;t.dm:934086a686-4;x:unknown,other=whatever'  | [_dd.p.dm: 934086a686-4]                                    ",
    "dd with xyz instead of s                | 'other=whatever,dd=xyz:unknown;t.dm:934086a686-4'                      | 'dd=t.dm:934086a686-4;xyz:unknown,other=whatever'    | [_dd.p.dm: 934086a686-4]                                    ",
    "dd with trailing whitespace             | 'other=whatever,dd=t.dm:934086a686-4;xyz:unknown  '                    | 'dd=t.dm:934086a686-4;xyz:unknown,other=whatever'    | [_dd.p.dm: 934086a686-4]                                    ",
    "ws and tabs around members              | '\tsome=thing \t , dd=s:0;t.dm:934086a687-3\t\t,  other=whatever\t\t ' | 'dd=s:0;t.dm:934086a687-3,some=thing,other=whatever' | [_dd.p.dm: 934086a687-3]                                    ",
    "dd with two t. tags                     | 'dd=s:0;t.a:b;t.x:y'                                                   | 'dd=s:0;t.a:b;t.x:y'                                 | [_dd.p.a: b, _dd.p.x: y]                                    ",
    "dd with two t. tags trailing whitespace | 'dd=s:0;t.a:b;t.x:y \t'                                                | 'dd=s:0;t.a:b;t.x:y'                                 | [_dd.p.a: b, _dd.p.x: y]                                    ",
    "dd with two t. tags inner whitespace    | 'dd=s:0;t.a:b ;t.x:y \t'                                               | 'dd=s:0;t.a:b ;t.x:y'                                | ['_dd.p.a': 'b ', _dd.p.x: y]                               ",
    "dd with two t. tags invalid whitespace  | 'dd=s:0;t.a:b \t;t.x:y \t'                                             |                                                      | [:]                                                         ",
    "dd with tid                             | 'dd=s:0;t.tid:123456789abcdef0'                                        | 'dd=s:0;t.tid:123456789abcdef0'                      | [_dd.p.tid: 123456789abcdef0]                               ",
    "tid empty value                         | 'dd=t.tid:'                                                            |                                                      | [:]                                                         ",
    "tid too short length 1                  | 'dd=t.tid:1'                                                           |                                                      | ['_dd.propagation_error': 'malformed_tid 1']                ",
    "tid too short length 15                 | 'dd=t.tid:111111111111111'                                             |                                                      | ['_dd.propagation_error': 'malformed_tid 111111111111111']  ",
    "tid too long length 17                  | 'dd=t.tid:11111111111111111'                                           |                                                      | ['_dd.propagation_error': 'malformed_tid 11111111111111111']",
    "tid uppercase                           | 'dd=t.tid:123456789ABCDEF0'                                            |                                                      | ['_dd.propagation_error': 'malformed_tid 123456789ABCDEF0'] ",
    "tid non-hex character                   | 'dd=t.tid:123456789abcdefg'                                            |                                                      | ['_dd.propagation_error': 'malformed_tid 123456789abcdefg'] ",
    "tid negative                            | 'dd=t.tid:-123456789abcdef'                                            |                                                      | ['_dd.propagation_error': 'malformed_tid -123456789abcdef'] "
  })
  void createPropagationTagsFromHeaderValue(
      String headerValue, String expectedHeaderValue, Map<String, String> tags) {
    PropagationTags propagationTags = factory().fromHeaderValue(HeaderType.W3C, headerValue);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.W3C));
    assertEquals(tags, propagationTags.createTagMap());
  }

  @TableTest({
    "scenario             | headerValue                                                  | expectedHeaderValue                                | tags                                                     ",
    "single dm            | 'dd=s:0;t.dm:934086a686-4'                                   | '_dd.p.dm=934086a686-4'                            | [_dd.p.dm: 934086a686-4]                                 ",
    "dm and arbitrary t.f | 'other=whatever,dd=s:0;t.dm:934086a686-4;t.f:w00t~~'         | '_dd.p.dm=934086a686-4,_dd.p.f=w00t=='             | [_dd.p.dm: 934086a686-4, _dd.p.f: 'w00t==']              ",
    "ts only              | 'dd=s:0;t.ts:02'                                             | '_dd.p.ts=02'                                      | [_dd.p.ts: 02]                                           ",
    "ts zero              | 'dd=s:0;t.ts:00'                                             |                                                    | [:]                                                      ",
    "ts too short         | 'dd=s:0;t.ts:0'                                              |                                                    | [:]                                                      ",
    "ts invalid           | 'dd=s:0;t.ts:invalid'                                        |                                                    | [:]                                                      ",
    "dm and t.f and ts    | 'other=whatever,dd=s:0;t.dm:934086a686-4;t.f:w00t~~;t.ts:02' | '_dd.p.dm=934086a686-4,_dd.p.ts=02,_dd.p.f=w00t==' | [_dd.p.dm: 934086a686-4, _dd.p.f: 'w00t==', _dd.p.ts: 02]",
    "no dd                | 'some=thing,other=whatever'                                  |                                                    | [:]                                                      "
  })
  void w3cPropagationTagsShouldTranslateToDatadogTags(
      String headerValue, String expectedHeaderValue, Map<String, String> tags) {
    PropagationTags propagationTags = factory().fromHeaderValue(HeaderType.W3C, headerValue);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.DATADOG));
    assertEquals(tags, propagationTags.createTagMap());
  }

  @TableTest({
    "scenario               | headerValue                       | priority                      | mechanism                           | origin | expectedHeaderValue                | tags                    ",
    "sampler keep default   | 'dd=s:0;o:some;t.dm:934086a686-4' | PrioritySampling.SAMPLER_KEEP | SamplingMechanism.DEFAULT           | other  | 'dd=s:0;o:other;t.dm:934086a686-4' | [_dd.p.dm: 934086a686-4]",
    "user keep local rule   | 'dd=s:0;o:some;x:unknown'         | PrioritySampling.USER_KEEP    | SamplingMechanism.LOCAL_USER_RULE   | same   | 'dd=s:2;o:same;t.dm:-3;x:unknown'  | [_dd.p.dm: '-3']        ",
    "user drop manual       | 'dd=s:0;o:some;x:unknown'         | PrioritySampling.USER_DROP    | SamplingMechanism.MANUAL            |        | 'dd=s:-1;x:unknown'                | [:]                     ",
    "keep external override | 'dd=s:0;o:some;t.dm:934086a686-4' | PrioritySampling.SAMPLER_KEEP | SamplingMechanism.EXTERNAL_OVERRIDE | other  | 'dd=s:1;o:other;t.dm:-0'           | [_dd.p.dm: '-0']        ",
    "drop external override | 'dd=s:1;o:some;t.dm:934086a686-4' | PrioritySampling.SAMPLER_DROP | SamplingMechanism.EXTERNAL_OVERRIDE | other  | 'dd=s:0;o:other'                   | [:]                     "
  })
  void propagationTagsShouldBeUpdatedBySamplingAndOrigin(
      String headerValue,
      @ConvertWith(PrioritySamplingConverter.class) int priority,
      @ConvertWith(SamplingMechanismConverter.class) int mechanism,
      String origin,
      String expectedHeaderValue,
      Map<String, String> tags) {
    PropagationTags propagationTags = factory().fromHeaderValue(HeaderType.W3C, headerValue);

    assertNotEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.W3C));

    propagationTags.updateTraceSamplingPriority(priority, mechanism);
    propagationTags.updateTraceOrigin(origin);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.W3C));
    assertEquals(tags, propagationTags.createTagMap());
  }

  @TableTest({
    "scenario        | headerValue            | product                | expectedHeaderValue    | tags          ",
    "asm on unknown  | 'dd=x:unknown'         | ProductTraceSource.ASM | 'dd=t.ts:02;x:unknown' | [_dd.p.ts: 02]",
    "dbm on existing | 'dd=t.ts:02;x:unknown' | ProductTraceSource.DBM | 'dd=t.ts:12;x:unknown' | [_dd.p.ts: 12]",
    "asm on ts zero  | 'dd=t.ts:00'           | ProductTraceSource.ASM | 'dd=t.ts:02'           | [_dd.p.ts: 02]",
    "asm on ts max   | 'dd=t.ts:FFC00000'     | ProductTraceSource.ASM | 'dd=t.ts:02'           | [_dd.p.ts: 02]"
  })
  void propagationTagsShouldBeUpdatedByProductTraceSourcePropagation(
      String headerValue,
      @ConvertWith(ProductTraceSourceConverter.class) int product,
      String expectedHeaderValue,
      Map<String, String> tags) {
    PropagationTags propagationTags = factory().fromHeaderValue(HeaderType.W3C, headerValue);

    assertNotEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.W3C));

    propagationTags.addTraceSource(product);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.W3C));
    assertEquals(tags, propagationTags.createTagMap());
  }

  private static String buildHeader(int memberCount) {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= memberCount; i++) {
      if (sb.length() > 0) sb.append(',');
      sb.append('k').append(i).append("=v").append(i);
    }
    return sb.toString();
  }

  private static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder(s.length() * n);
    for (int i = 0; i < n; i++) sb.append(s);
    return sb.toString();
  }

  private static String toLcAlpha(String cs) {
    // Argh groovy and characters
    char c = cs.charAt(0);
    char a = 'a';
    char z = 'z';
    return String.valueOf((char) (a + (Math.abs(c - a) % (z - a))));
  }
}
