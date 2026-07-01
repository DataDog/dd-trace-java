package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static datadog.trace.core.propagation.PropagationTags.factory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.junit.utils.converter.ProductTraceSourceConverter;
import datadog.trace.junit.utils.converter.SamplingMechanismConverter;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class DatadogPropagationTagsTest extends DDJavaSpecification {
  @TableTest({
    "scenario                          | headerValue                                                                                                                  | expectedHeaderValue                        | tags                                                      ",
    "null input                        |                                                                                                                              |                                            | [:]                                                       ",
    "empty input                       | ''                                                                                                                           |                                            | [:]                                                       ",
    "valid dm tag short                | '_dd.p.dm=934086a686-4'                                                                                                      | '_dd.p.dm=934086a686-4'                    | [_dd.p.dm: '934086a686-4']                                ",
    "valid dm tag 2-digit              | '_dd.p.dm=934086a686-10'                                                                                                     | '_dd.p.dm=934086a686-10'                   | [_dd.p.dm: '934086a686-10']                               ",
    "valid dm tag 3-digit              | '_dd.p.dm=934086a686-102'                                                                                                    | '_dd.p.dm=934086a686-102'                  | [_dd.p.dm: '934086a686-102']                              ",
    "dm tag minus only                 | '_dd.p.dm=-1'                                                                                                                | '_dd.p.dm=-1'                              | [_dd.p.dm: '-1']                                          ",
    "any p tag                         | '_dd.p.anytag=value'                                                                                                         | '_dd.p.anytag=value'                       | [_dd.p.anytag: 'value']                                   ",
    "non p tag dropped                 | '_dd.b.somekey=value'                                                                                                        |                                            | [:]                                                       ",
    "upstream services alone dropped   | '_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1'                                                                            |                                            | [:]                                                       ",
    "dm and anytag                     | '_dd.p.dm=934086a686-4,_dd.p.anytag=value'                                                                                   | '_dd.p.dm=934086a686-4,_dd.p.anytag=value' | [_dd.p.dm: '934086a686-4', _dd.p.anytag: 'value']         ",
    "dm with upstream and anytag       | '_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value'                                   | '_dd.p.dm=934086a686-4,_dd.p.anytag=value' | [_dd.p.dm: '934086a686-4', _dd.p.anytag: 'value']         ",
    "ddb keyonly with dm upstream      | '_dd.b.keyonly=value,_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value'               | '_dd.p.dm=934086a686-4,_dd.p.anytag=value' | [_dd.p.dm: '934086a686-4', _dd.p.anytag: 'value']         ",
    "valid p tag with spaces           | '_dd.p.ab=1 2 3'                                                                                                             | '_dd.p.ab=1 2 3'                           | [_dd.p.ab: '1 2 3']                                       ",
    "valid p tag leading trail spc     | '_dd.p.ab= 123 '                                                                                                             | '_dd.p.ab= 123 '                           | [_dd.p.ab: ' 123 ']                                       ",
    "key only error                    | '_dd.p.keyonly'                                                                                                              |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "leading comma error               | ',_dd.p.dm=Value'                                                                                                            |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "comma only error                  | ','                                                                                                                          |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "ddb keyonly with embedded keyonly | '_dd.b.somekey=value,_dd.p.dm=934086a686-4,_dd.p.keyonly,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value' |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "embedded keyonly with dm upstream | '_dd.p.keyonly,_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value'                     |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "leading comma with dm upstream    | ',_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value'                                  |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "double comma in tagset            | '_dd.p.dm=934086a686-4,,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value'                                  |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "space tag in tagset               | '_dd.p.dm=934086a686-4, ,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value'                                 |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "upstream variant dropped alone    | '_dd.p.upstream_services=bmV1dHJvbg==|0|1|0.2253'                                                                            |                                            | [:]                                                       ",
    "leading space error               | ' _dd.p.ab=123'                                                                                                              |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "key with space error              | '_dd.p.a b=123'                                                                                                              |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "trailing key space error          | '_dd.p.ab =123'                                                                                                              |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "space inside key error            | '_dd.p. ab=123'                                                                                                              |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "tag with eq value                 | '_dd.p.a=b=1=2'                                                                                                              | '_dd.p.a=b=1=2'                            | [_dd.p.a: 'b=1=2']                                        ",
    "invalid key non-ascii             | '_dd.p.1ö2=value'                                                                                                            |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "value with equals                 | '_dd.p.ab=1=2'                                                                                                               | '_dd.p.ab=1=2'                             | [_dd.p.ab: '1=2']                                         ",
    "invalid value non-ascii           | '_dd.p.ab=1ô2'                                                                                                               |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "dm tag upper case                 | '_dd.p.dm=934086A686-4'                                                                                                      |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "dm tag too short                  | '_dd.p.dm=934086a66-4'                                                                                                       |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "dm tag too long                   | '_dd.p.dm=934086a6653-4'                                                                                                     |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "dm tag missing separator          | '_dd.p.dm=934086a66534'                                                                                                      |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "dm tag missing mechanism          | '_dd.p.dm=934086a665-'                                                                                                       |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "dm tag invalid mechanism char     | '_dd.p.dm=934086a665-a'                                                                                                      |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "dm tag mechanism with letter      | '_dd.p.dm=934086a665-12b'                                                                                                    |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "tid empty                         | '_dd.p.tid='                                                                                                                 |                                            | [_dd.propagation_error: 'decoding_error']                 ",
    "tid length 1                      | '_dd.p.tid=1'                                                                                                                |                                            | [_dd.propagation_error: 'malformed_tid 1']                ",
    "tid length 15                     | '_dd.p.tid=111111111111111'                                                                                                  |                                            | [_dd.propagation_error: 'malformed_tid 111111111111111']  ",
    "tid length 17                     | '_dd.p.tid=11111111111111111'                                                                                                |                                            | [_dd.propagation_error: 'malformed_tid 11111111111111111']",
    "tid invalid uppercase             | '_dd.p.tid=123456789ABCDEF0'                                                                                                 |                                            | [_dd.propagation_error: 'malformed_tid 123456789ABCDEF0'] ",
    "tid invalid non-hex               | '_dd.p.tid=123456789abcdefg'                                                                                                 |                                            | [_dd.propagation_error: 'malformed_tid 123456789abcdefg'] ",
    "tid invalid negative              | '_dd.p.tid=-123456789abcdef'                                                                                                 |                                            | [_dd.propagation_error: 'malformed_tid -123456789abcdef'] ",
    "ts valid 02                       | '_dd.p.ts=02'                                                                                                                | '_dd.p.ts=02'                              | [_dd.p.ts: '02']                                          ",
    "ts zero dropped                   | '_dd.p.ts=00'                                                                                                                |                                            | [:]                                                       ",
    "ts invalid foo                    | '_dd.p.ts=foo'                                                                                                               |                                            | [_dd.propagation_error: 'decoding_error']                 "
  })
  void createPropagationTagsFromHeaderValue(
      String headerValue, String expectedHeaderValue, Map<String, String> tags) {
    PropagationTags propagationTags = factory().fromHeaderValue(DATADOG, headerValue);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(DATADOG));
    assertEquals(tags, propagationTags.createTagMap());
  }

  @TableTest({
    "scenario          | headerValue                            | expectedHeaderValue               | tags                                         ",
    "single dm tag     | '_dd.p.dm=934086a686-4'                | 'dd=t.dm:934086a686-4'            | [_dd.p.dm: '934086a686-4']                   ",
    "dm and f tag      | '_dd.p.dm=934086a686-4,_dd.p.f=w00t==' | 'dd=t.dm:934086a686-4;t.f:w00t~~' | [_dd.p.dm: '934086a686-4', _dd.p.f: 'w00t==']",
    "dm and appsec tag | '_dd.p.dm=934086a686-4,_dd.p.appsec=1' | 'dd=t.dm:934086a686-4;t.appsec:1' | [_dd.p.dm: '934086a686-4', _dd.p.appsec: '1']"
  })
  void datadogPropagationTagsShouldTranslateToW3cTags(
      String headerValue, String expectedHeaderValue, Map<String, String> tags) {
    PropagationTags propagationTags = factory().fromHeaderValue(DATADOG, headerValue);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(W3C));
    assertEquals(tags, propagationTags.createTagMap());
  }

  @TableTest({
    "scenario                          | originalTagSet                                              | priority     | mechanism                    | expectedHeaderValue                                         | tags                                                                  ",
    "keep dm unchanged unset           | '_dd.p.dm=934086a686-4'                                     | UNSET        | SamplingMechanism.UNKNOWN    | '_dd.p.dm=934086a686-4'                                     | [_dd.p.dm: '934086a686-4']                                            ",
    "keep dm unchanged sampler keep    | '_dd.p.dm=934086a686-3'                                     | SAMPLER_KEEP | SamplingMechanism.AGENT_RATE | '_dd.p.dm=934086a686-3'                                     | [_dd.p.dm: '934086a686-3']                                            ",
    "keep dm unchanged appsec          | '_dd.p.dm=93485302ab-1'                                     | SAMPLER_KEEP | SamplingMechanism.APPSEC     | '_dd.p.dm=93485302ab-1'                                     | [_dd.p.dm: '93485302ab-1']                                            ",
    "keep dm with anytag               | '_dd.p.dm=934086a686-4,_dd.p.anytag=value'                  | SAMPLER_KEEP | SamplingMechanism.AGENT_RATE | '_dd.p.dm=934086a686-4,_dd.p.anytag=value'                  | [_dd.p.dm: '934086a686-4', _dd.p.anytag: 'value']                     ",
    "keep dm with anytag appsec        | '_dd.p.dm=93485302ab-2,_dd.p.anytag=value'                  | SAMPLER_KEEP | SamplingMechanism.APPSEC     | '_dd.p.dm=93485302ab-2,_dd.p.anytag=value'                  | [_dd.p.dm: '93485302ab-2', _dd.p.anytag: 'value']                     ",
    "dm moves to front                 | '_dd.p.anytag=value,_dd.p.dm=934086a686-4'                  | SAMPLER_KEEP | SamplingMechanism.AGENT_RATE | '_dd.p.dm=934086a686-4,_dd.p.anytag=value'                  | [_dd.p.anytag: 'value', _dd.p.dm: '934086a686-4']                     ",
    "dm moves to front appsec          | '_dd.p.anytag=value,_dd.p.dm=93485302ab-2'                  | SAMPLER_KEEP | SamplingMechanism.APPSEC     | '_dd.p.dm=93485302ab-2,_dd.p.anytag=value'                  | [_dd.p.anytag: 'value', _dd.p.dm: '93485302ab-2']                     ",
    "dm reordered with multiple tags   | '_dd.p.anytag=value,_dd.p.dm=934086a686-4,_dd.p.atag=value' | SAMPLER_KEEP | SamplingMechanism.AGENT_RATE | '_dd.p.dm=934086a686-4,_dd.p.anytag=value,_dd.p.atag=value' | [_dd.p.anytag: 'value', _dd.p.dm: '934086a686-4', _dd.p.atag: 'value']",
    "dm reordered multiple tags appsec | '_dd.p.anytag=value,_dd.p.dm=93485302ab-2,_dd.p.atag=value' | SAMPLER_KEEP | SamplingMechanism.APPSEC     | '_dd.p.dm=93485302ab-2,_dd.p.anytag=value,_dd.p.atag=value' | [_dd.p.anytag: 'value', _dd.p.dm: '93485302ab-2', _dd.p.atag: 'value']",
    "user drop manual single dm        | '_dd.p.dm=93485302ab-2'                                     | USER_DROP    | SamplingMechanism.MANUAL     | '_dd.p.dm=93485302ab-2'                                     | [_dd.p.dm: '93485302ab-2']                                            ",
    "sampler drop manual reorder       | '_dd.p.anytag=value,_dd.p.dm=93485302ab-2'                  | SAMPLER_DROP | SamplingMechanism.MANUAL     | '_dd.p.dm=93485302ab-2,_dd.p.anytag=value'                  | [_dd.p.anytag: 'value', _dd.p.dm: '93485302ab-2']                     ",
    "user drop manual                  | '_dd.p.dm=93485302ab-2,_dd.p.anytag=value'                  | USER_DROP    | SamplingMechanism.MANUAL     | '_dd.p.dm=93485302ab-2,_dd.p.anytag=value'                  | [_dd.p.dm: '93485302ab-2', _dd.p.anytag: 'value']                     ",
    "user drop manual triple           | '_dd.p.atag=value,_dd.p.dm=93485302ab-2,_dd.p.anytag=value' | USER_DROP    | SamplingMechanism.MANUAL     | '_dd.p.dm=93485302ab-2,_dd.p.atag=value,_dd.p.anytag=value' | [_dd.p.atag: 'value', _dd.p.dm: '93485302ab-2', _dd.p.anytag: 'value']",
    "empty sampler keep default        | ''                                                          | SAMPLER_KEEP | SamplingMechanism.DEFAULT    | '_dd.p.dm=-0'                                               | [_dd.p.dm: '-0']                                                      ",
    "empty sampler keep agent rate     | ''                                                          | SAMPLER_KEEP | SamplingMechanism.AGENT_RATE | '_dd.p.dm=-1'                                               | [_dd.p.dm: '-1']                                                      ",
    "anytag user keep manual           | '_dd.p.anytag=value'                                        | USER_KEEP    | SamplingMechanism.MANUAL     | '_dd.p.dm=-4,_dd.p.anytag=value'                            | [_dd.p.anytag: 'value', _dd.p.dm: '-4']                               ",
    "no dm change sampler drop manual  | '_dd.p.anytag=value,_dd.p.atag=value'                       | SAMPLER_DROP | SamplingMechanism.MANUAL     | '_dd.p.anytag=value,_dd.p.atag=value'                       | [_dd.p.anytag: 'value', _dd.p.atag: 'value']                          ",
    "no dm when mechanism unknown      | '_dd.p.anytag=123'                                          | SAMPLER_KEEP | SamplingMechanism.UNKNOWN    | '_dd.p.anytag=123'                                          | [_dd.p.anytag: '123']                                                 ",
    "invalid input still updates dm    | ',_dd.p.dm=Value'                                           | SAMPLER_KEEP | SamplingMechanism.AGENT_RATE | '_dd.p.dm=-1'                                               | [_dd.propagation_error: 'decoding_error', _dd.p.dm: '-1']             "
  })
  void updatePropagationTagsSamplingMechanism(
      String originalTagSet,
      @ConvertWith(PrioritySamplingConverter.class) byte priority,
      @ConvertWith(SamplingMechanismConverter.class) byte mechanism,
      String expectedHeaderValue,
      Map<String, String> tags) {
    PropagationTags propagationTags = factory().fromHeaderValue(DATADOG, originalTagSet);

    propagationTags.updateTraceSamplingPriority(priority, mechanism);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(DATADOG));
    assertEquals(tags, propagationTags.createTagMap());
  }

  @TableTest({
    "scenario             | originalTagSet       | product                  | expectedHeaderValue | tags                                     ",
    "set ts on empty      | ''                   | ProductTraceSource.ASM   | '_dd.p.ts=02'       | [_dd.p.ts: '02']                         ",
    "promote from 00      | '_dd.p.ts=00'        | ProductTraceSource.ASM   | '_dd.p.ts=02'       | [_dd.p.ts: '02']                         ",
    "demote from FFC00000 | '_dd.p.ts=FFC00000'  | ProductTraceSource.ASM   | '_dd.p.ts=02'       | [_dd.p.ts: '02']                         ",
    "add dbm on 02        | '_dd.p.ts=02'        | ProductTraceSource.DBM   | '_dd.p.ts=12'       | [_dd.p.ts: '12']                         ",
    "invalid empty        | '_dd.p.ts='          | ProductTraceSource.UNSET |                     | [_dd.propagation_error: 'decoding_error']",
    "invalid single 0     | '_dd.p.ts=0'         | ProductTraceSource.UNSET |                     | [_dd.propagation_error: 'decoding_error']",
    "invalid char in pair | '_dd.p.ts=0G'        | ProductTraceSource.UNSET |                     | [_dd.propagation_error: 'decoding_error']",
    "invalid chars only   | '_dd.p.ts=GG'        | ProductTraceSource.UNSET |                     | [_dd.propagation_error: 'decoding_error']",
    "invalid foo          | '_dd.p.ts=foo'       | ProductTraceSource.UNSET |                     | [_dd.propagation_error: 'decoding_error']",
    "invalid too long     | '_dd.p.ts=000000002' | ProductTraceSource.UNSET |                     | [_dd.propagation_error: 'decoding_error']"
  })
  void updatePropagationTagsTraceSourcePropagation(
      String originalTagSet,
      @ConvertWith(ProductTraceSourceConverter.class) int product,
      String expectedHeaderValue,
      Map<String, String> tags) {
    PropagationTags propagationTags = factory().fromHeaderValue(DATADOG, originalTagSet);

    propagationTags.addTraceSource(product);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(DATADOG));
    assertEquals(tags, propagationTags.createTagMap());
  }

  @Test
  void extractionLimitExceeded() {
    String tags = "_dd.p.anytag=value";
    int limit = tags.length() - 1;
    PropagationTags propagationTags = factory(limit).fromHeaderValue(DATADOG, tags);

    propagationTags.updateTraceSamplingPriority(USER_KEEP, MANUAL);

    assertEquals("_dd.p.dm=-4", propagationTags.headerValue(DATADOG));
    Map<String, String> expected = new HashMap<>();
    expected.put("_dd.propagation_error", "extract_max_size");
    expected.put("_dd.p.dm", "-4");
    assertEquals(expected, propagationTags.createTagMap());
  }

  @Test
  void injectionLimitExceeded() {
    String tags = "_dd.p.anytag=value";
    int limit = tags.length();
    PropagationTags propagationTags = factory(limit).fromHeaderValue(DATADOG, tags);

    propagationTags.updateTraceSamplingPriority(USER_KEEP, MANUAL);

    assertNull(propagationTags.headerValue(DATADOG));
    Map<String, String> expected = new HashMap<>();
    expected.put("_dd.propagation_error", "inject_max_size");
    assertEquals(expected, propagationTags.createTagMap());
  }

  @Test
  void injectionLimitExceededLimit0() {
    PropagationTags propagationTags = factory(0).fromHeaderValue(DATADOG, "");

    propagationTags.updateTraceSamplingPriority(USER_KEEP, MANUAL);

    assertNull(propagationTags.headerValue(DATADOG));
    Map<String, String> expected = new HashMap<>();
    expected.put("_dd.propagation_error", "disabled");
    assertEquals(expected, propagationTags.createTagMap());
  }
}
