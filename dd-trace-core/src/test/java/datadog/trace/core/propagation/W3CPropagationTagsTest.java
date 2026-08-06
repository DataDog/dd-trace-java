package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.PropagationTags.HeaderType;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static datadog.trace.core.propagation.PropagationTags.factory;
import static datadog.trace.core.propagation.ptags.W3CPTagsCodec.MAX_MEMBER_COUNT;
import static java.lang.Math.min;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.stream.IntStream.concat;
import static java.util.stream.IntStream.rangeClosed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.test.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.test.junit.utils.converter.ProductTraceSourceConverter;
import datadog.trace.test.junit.utils.converter.SamplingMechanismConverter;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class W3CPropagationTagsTest extends DDCoreJavaSpecification {

  @ParameterizedTest
  @MethodSource("validateTracestateHeaderLimitsArguments")
  void validateTracestateHeaderLimits(String headerValue, boolean valid) {
    PropagationTags propagationTags = factory().fromHeaderValue(W3C, headerValue);

    if (valid) {
      assertEquals(headerValue.trim(), propagationTags.headerValue(W3C));
    } else {
      assertNull(propagationTags.headerValue(W3C));
    }
    // we're not using any dd members in the tests
    assertTrue(propagationTags.createTagMap().isEmpty());
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
    String lcAlpha = toLowerCaseAlpha(headerChar);
    String simpleKeyHeader = lcAlpha + headerChar + "_-*/=1";
    String multiKeyHeader = headerChar + "@" + lcAlpha + headerChar + "_-*/=1";

    PropagationTags simpleKeyPT = factory().fromHeaderValue(W3C, simpleKeyHeader);
    PropagationTags multiKeyPT = factory().fromHeaderValue(W3C, multiKeyHeader);

    assertEquals(simpleKeyHeader, simpleKeyPT.headerValue(W3C));
    assertEquals(multiKeyHeader, multiKeyPT.headerValue(W3C));
    // we're not using any dd members in the tests
    assertTrue(simpleKeyPT.createTagMap().isEmpty());
    assertTrue(multiKeyPT.createTagMap().isEmpty());
  }

  static Stream<Arguments> validateTracestateHeaderValidKeyContentsArguments() {
    return concat(rangeClosed('a', 'z'), rangeClosed('0', '9'))
        .mapToObj(c -> arguments(String.valueOf((char) c)));
  }

  @ParameterizedTest
  @MethodSource("validateTracestateHeaderInvalidKeyContentsArguments")
  void validateTracestateHeaderInvalidKeyContents(String headerChar) {
    String lcAlpha = toLowerCaseAlpha(headerChar);
    String simpleKeyHeader = lcAlpha + headerChar + "_-*/=1";
    String multiKeyHeader = lcAlpha + headerChar + "@" + lcAlpha + headerChar + "_-*/=1";

    PropagationTags simpleKeyPT = factory().fromHeaderValue(W3C, simpleKeyHeader);
    PropagationTags multiKeyPT = factory().fromHeaderValue(W3C, multiKeyHeader);

    assertNull(simpleKeyPT.headerValue(W3C));
    assertNull(multiKeyPT.headerValue(W3C));
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), simpleKeyPT.createTagMap());
    assertEquals(emptyMap(), multiKeyPT.createTagMap());
  }

  static Stream<Arguments> validateTracestateHeaderInvalidKeyContentsArguments() {
    return rangeClosed(' ', 'ÿ')
        .filter(W3CPropagationTagsTest::invalidKeyChar)
        .mapToObj(value -> String.valueOf((char) value))
        .map(Arguments::of);
  }

  static boolean invalidKeyChar(int i) {
    if (i >= 'a' && i <= 'z') {
      return false;
    }
    if (i >= '0' && i <= '9') {
      return false;
    }
    return i != '_' && i != '-' && i != '*' && i != '/';
  }

  @ParameterizedTest
  @MethodSource("validateTracestateHeaderValidValueContentsArguments")
  void validateTracestateHeaderValidValueContents(String valueChar) {
    String lcAlpha = toLowerCaseAlpha(valueChar);
    String mostlyOkHeader = lcAlpha + "=" + valueChar;
    String alwaysOkHeader = lcAlpha + "=" + lcAlpha + valueChar + lcAlpha;

    PropagationTags mostlyOkPT = factory().fromHeaderValue(W3C, mostlyOkHeader);
    PropagationTags alwaysOkPT = factory().fromHeaderValue(W3C, alwaysOkHeader);

    if (" ".equals(valueChar)) {
      assertNull(mostlyOkPT.headerValue(W3C));
    } else {
      assertEquals(mostlyOkHeader, mostlyOkPT.headerValue(W3C));
    }
    assertEquals(alwaysOkHeader, alwaysOkPT.headerValue(W3C));
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), mostlyOkPT.createTagMap());
    assertEquals(emptyMap(), alwaysOkPT.createTagMap());
  }

  static Stream<Arguments> validateTracestateHeaderValidValueContentsArguments() {
    return rangeClosed(' ', '~')
        .filter(c -> c != ',' && c != '=')
        .mapToObj(String::valueOf)
        .map(Arguments::of);
  }

  @ParameterizedTest
  @MethodSource("validateTracestateHeaderInvalidValueContentsArguments")
  void validateTracestateHeaderInvalidValueContents(String valueChar) {
    String lcAlpha = toLowerCaseAlpha(valueChar);
    String alwaysBadHeader = lcAlpha + "=" + lcAlpha + valueChar + lcAlpha;

    PropagationTags alwaysBadPT = factory().fromHeaderValue(W3C, alwaysBadHeader);

    assertNull(alwaysBadPT.headerValue(W3C));
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), alwaysBadPT.createTagMap());
  }

  static Stream<Arguments> validateTracestateHeaderInvalidValueContentsArguments() {
    return rangeClosed(' ', 'ÿ')
        .filter(W3CPropagationTagsTest::invalidValueChar)
        .mapToObj(value -> String.valueOf((char) value))
        .map(Arguments::of);
  }

  static boolean invalidValueChar(int i) {
    if (i >= ' ' && i <= '~') {
      return i == ',' || i == '=';
    }
    return true;
  }

  @ParameterizedTest(name = "{0} members")
  @MethodSource("memberCountArguments")
  void validateTracestateHeaderNumberOfMembersWithoutDatadogMember(int memberCount) {
    String header = buildHeader(memberCount);

    PropagationTags headerPT = factory().fromHeaderValue(W3C, header);

    if (memberCount <= 32) {
      assertEquals(header, headerPT.headerValue(W3C));
    } else {
      assertNull(headerPT.headerValue(W3C));
    }
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), headerPT.createTagMap());
  }

  @ParameterizedTest(name = "{0} members")
  @MethodSource("memberCountArguments")
  void validateTracestateHeaderNumberOfMembersWithDatadogMember(int memberCount) {
    String header = "dd=s:1," + buildHeader(memberCount);

    PropagationTags headerPT = factory().fromHeaderValue(W3C, header);

    if (memberCount + 1 <= 32) {
      assertEquals(header, headerPT.headerValue(W3C));
    } else {
      assertNull(headerPT.headerValue(W3C));
    }
    // we're not using any dd members in the tests
    assertEquals(emptyMap(), headerPT.createTagMap());
  }

  @ParameterizedTest(name = "{0} members")
  @MethodSource("memberCountArguments")
  void validateTracestateHeaderNumberOfMembersWhenPropagatingOriginalTracestate(int memberCount) {
    String header = buildHeader(memberCount);
    String expectedHeader =
        "dd=t.dm:-4," + (memberCount > MAX_MEMBER_COUNT ? "" : buildHeader(min(memberCount, 31)));

    PropagationTags datadogHeaderPT = factory().fromHeaderValue(HeaderType.DATADOG, "_dd.p.dm=-4");
    PropagationTags headerPT = factory().fromHeaderValue(W3C, header);
    datadogHeaderPT.updateW3CTracestate(headerPT.getW3CTracestate());

    if (memberCount <= 32) {
      assertEquals(expectedHeader, datadogHeaderPT.headerValue(W3C));
    } else {
      assertEquals("dd=t.dm:-4", datadogHeaderPT.headerValue(W3C));
    }
    assertEquals(singletonMap("_dd.p.dm", "-4"), datadogHeaderPT.createTagMap());
  }

  static IntStream memberCountArguments() {
    return rangeClosed(1, 37);
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
    "tid negative                            | 'dd=t.tid:-123456789abcdef'                                            |                                                      | ['_dd.propagation_error': 'malformed_tid -123456789abcdef'] ",
    "dd with trailing separator              | 'dd=s:0;t.dm:934086a686-4;'                                            | 'dd=s:0;t.dm:934086a686-4'                           | [_dd.p.dm: 934086a686-4]                                    ",
    "dd single tag trailing separator        | 'dd=t.dm:934086a686-4;'                                                | 'dd=t.dm:934086a686-4'                               | [_dd.p.dm: 934086a686-4]                                    ",
    "dd before other trailing separator      | 'dd=s:0;t.dm:934086a687-3;,other=whatever'                             | 'dd=s:0;t.dm:934086a687-3,other=whatever'            | [_dd.p.dm: 934086a687-3]                                    ",
    "dd trailing separator then ws           | 'dd=s:0;t.dm:934086a686-4;  '                                          | 'dd=s:0;t.dm:934086a686-4'                           | [_dd.p.dm: 934086a686-4]                                    ",
    "dd trailing separator then tab          | 'dd=s:0;t.dm:934086a686-4;\t'                                          | 'dd=s:0;t.dm:934086a686-4'                           | [_dd.p.dm: 934086a686-4]                                    ",
    "dd trailing separator ws then comma     | 'dd=s:0;t.dm:934086a687-3;  ,other=whatever'                           | 'dd=s:0;t.dm:934086a687-3,other=whatever'            | [_dd.p.dm: 934086a687-3]                                    ",
    "unknown trailing separator              | 'dd=x:y;'                                                              | 'dd=x:y'                                             | [:]                                                         ",
    "unknown trailing separator then space   | 'dd=x:y; '                                                             | 'dd=x:y'                                             | [:]                                                         ",
    "unknown trailing separator then tab     | 'dd=x:y;\t'                                                            | 'dd=x:y'                                             | [:]                                                         ",
    "sampling unknown trailing sep then ws   | 'dd=s:0;x:y; '                                                         | 'dd=s:0;x:y'                                         | [:]                                                         ",
    "dd interior ws between submembers       | 'dd=s:0;t.dm:934086a686-4;  t.x:y'                                     |                                                      | [:]                                                         ",
    "dd interior single space submember      | 'dd=s:0; t.dm:934086a686-4'                                            |                                                      | [:]                                                         "
  })
  void createPropagationTagsFromHeaderValue(
      String headerValue, String expectedHeaderValue, Map<String, String> tags) {
    PropagationTags propagationTags = factory().fromHeaderValue(W3C, headerValue);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(W3C));
    assertEquals(tags, propagationTags.createTagMap());
  }

  @TableTest({
    "scenario                          | headerValue              | expectedLastParentId | expectedHeaderValue    ",
    "parent id                         | 'dd=p:b6241412414a'      | b6241412414a         | 'dd=p:b6241412414a'    ",
    "parent id with trailing separator | 'dd=p:b6241412414a;'     | b6241412414a         | 'dd=p:b6241412414a'    ",
    "sampling then parent id trailing  | 'dd=s:1;p:b6241412414a;' | b6241412414a         | 'dd=s:1;p:b6241412414a'",
    "parent id trailing separator ws   | 'dd=p:b6241412414a;  '   | b6241412414a         | 'dd=p:b6241412414a'    "
  })
  void extractsLastParentIdWithTrailingSeparator(
      String headerValue, String expectedLastParentId, String expectedHeaderValue) {
    // trailing ';' in the dd tracestate member must not be treated as malformed: it is
    // tolerated and the parent id is still extracted
    PropagationTags propagationTags = factory().fromHeaderValue(HeaderType.W3C, headerValue);

    assertEquals(expectedLastParentId, propagationTags.getLastParentId().toString());
    assertEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.W3C));
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
    PropagationTags propagationTags = factory().fromHeaderValue(W3C, headerValue);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(HeaderType.DATADOG));
    assertEquals(tags, propagationTags.createTagMap());
  }

  @TableTest({
    "scenario               | headerValue                       | priority     | mechanism                           | origin | expectedHeaderValue                | tags                    ",
    "sampler keep default   | 'dd=s:0;o:some;t.dm:934086a686-4' | SAMPLER_KEEP | SamplingMechanism.DEFAULT           | other  | 'dd=s:0;o:other;t.dm:934086a686-4' | [_dd.p.dm: 934086a686-4]",
    "user keep local rule   | 'dd=s:0;o:some;x:unknown'         | USER_KEEP    | SamplingMechanism.LOCAL_USER_RULE   | same   | 'dd=s:2;o:same;t.dm:-3;x:unknown'  | [_dd.p.dm: '-3']        ",
    "user drop manual       | 'dd=s:0;o:some;x:unknown'         | USER_DROP    | SamplingMechanism.MANUAL            |        | 'dd=s:-1;x:unknown'                | [:]                     ",
    "keep external override | 'dd=s:0;o:some;t.dm:934086a686-4' | SAMPLER_KEEP | SamplingMechanism.EXTERNAL_OVERRIDE | other  | 'dd=s:1;o:other;t.dm:-0'           | [_dd.p.dm: '-0']        ",
    "drop external override | 'dd=s:1;o:some;t.dm:934086a686-4' | SAMPLER_DROP | SamplingMechanism.EXTERNAL_OVERRIDE | other  | 'dd=s:0;o:other'                   | [:]                     "
  })
  void propagationTagsShouldBeUpdatedBySamplingAndOrigin(
      String headerValue,
      @ConvertWith(PrioritySamplingConverter.class) byte priority,
      @ConvertWith(SamplingMechanismConverter.class) byte mechanism,
      String origin,
      String expectedHeaderValue,
      Map<String, String> tags) {
    PropagationTags propagationTags = factory().fromHeaderValue(W3C, headerValue);

    assertNotEquals(expectedHeaderValue, propagationTags.headerValue(W3C));

    propagationTags.updateTraceSamplingPriority(priority, mechanism);
    propagationTags.updateTraceOrigin(origin);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(W3C));
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
    PropagationTags propagationTags = factory().fromHeaderValue(W3C, headerValue);

    assertNotEquals(expectedHeaderValue, propagationTags.headerValue(W3C));

    propagationTags.addTraceSource(product);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(W3C));
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

  private static String toLowerCaseAlpha(String cs) {
    char c = cs.charAt(0);
    char a = 'a';
    char z = 'z';
    return String.valueOf((char) (a + (Math.abs(c - a) % (z - a))));
  }
}
