package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static datadog.trace.core.propagation.PropagationTags.factory;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.util.DDJavaSpecification;
import java.util.Map;
import org.tabletest.junit.TableTest;

/**
 * Keys the {@code x-datadog-tags} parse tells apart from their raw characters (without interning
 * them): known {@code _dd.p.*} keys, other {@code _dd.p.*} keys, and keys that aren't propagated.
 * Each row's expectations were recorded against the parse before it matched keys this way.
 */
class DatadogTagsKeyMatchingTest extends DDJavaSpecification {

  @TableTest({
    "scenario                   | headerValue                               | expectedHeaderValue                      | tags                                           ",
    "prefix only key ignored    | '_dd.p.=x,_dd.p.dm=-4'                    | '_dd.p.dm=-4'                            | [_dd.p.dm: '-4']                               ",
    "unprefixed key ignored     | 'foo=bar,_dd.p.dm=-4'                     | '_dd.p.dm=-4'                            | [_dd.p.dm: '-4']                               ",
    "near miss key is unknown   | '_dd.p.dmx=1,_dd.p.dm=-4'                 | '_dd.p.dmx=1,_dd.p.dm=-4'                | [_dd.p.dmx: '1', _dd.p.dm: '-4']               ",
    "unknown key kept           | '_dd.p.dm=-4,_dd.p.xyz=1'                 | '_dd.p.dm=-4,_dd.p.xyz=1'                | [_dd.p.dm: '-4', _dd.p.xyz: '1']               ",
    "upstream services dropped  | '_dd.p.upstream_services=abc,_dd.p.dm=-4' | '_dd.p.dm=-4'                            | [_dd.p.dm: '-4']                               ",
    "tid with dm                | '_dd.p.dm=-4,_dd.p.tid=640cfd8d00000000'  | '_dd.p.dm=-4,_dd.p.tid=640cfd8d00000000' | [_dd.p.dm: '-4', _dd.p.tid: '640cfd8d00000000']",
    "all zero tid kept verbatim | '_dd.p.tid=0000000000000000'              | '_dd.p.tid=0000000000000000'             | [_dd.p.tid: '0000000000000000']                ",
    "value with equals          | '_dd.p.dm=-4,_dd.p.f=w00t=='              | '_dd.p.dm=-4,_dd.p.f=w00t=='             | [_dd.p.dm: '-4', _dd.p.f: 'w00t==']            "
  })
  void parsesKeysFromTheirCharacters(
      String headerValue, String expectedHeaderValue, Map<String, String> tags) {
    PropagationTags propagationTags = factory().fromHeaderValue(DATADOG, headerValue);

    assertEquals(expectedHeaderValue, propagationTags.headerValue(DATADOG));
    assertEquals(tags, propagationTags.createTagMap());
  }
}
