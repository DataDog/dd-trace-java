package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.ProductTraceSource
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.core.propagation.PropagationTags.HeaderType

class W3CPropagationTagsTest extends DDCoreSpecification {

  def "validate tracestate header limits #headerValue"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)

    when:
    def propagationTags = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, headerValue)

    then:
    if (valid) {
      assert propagationTags.headerValue(HeaderType.W3C) == headerValue.trim()
    } else {
      assert propagationTags.headerValue(HeaderType.W3C) == null
    }
    // we're not using any dd members in the tests
    propagationTags.createTagMap() == [:]

    where:
    headerValue                       | valid
    null                              | false
    ''                                | false
    // check basic key length limit
    'k' * 251 + '0_-*/=1'             | true
    'k' * 252 + '0_-*/=1'             | false
    // check multi key length limit
    't' * 241 + '@' + 's' * 14 + '=1' | true
    't' * 242 + '@' + 's' * 14 + '=1' | false
    't' * 241 + '@' + 's' * 15 + '=1' | false
    // check value length limit
    'k=' + 'v' * 256                  | true
    'k=' + 'v' * 257                  | false
    // check value length limit with some trailing whitespace
    'k=' + 'v' * 256 + ' \t \t'       | true
    'k=' + 'v' * 257 + ' \t \t'       | false
  }

  def "validate tracestate header valid key contents '#headerChar'"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)
    def lcAlpha = toLcAlpha(headerChar)
    def simpleKeyHeader = lcAlpha + headerChar + '_-*/=1'
    def multiKeyHeader = headerChar + '@' + lcAlpha + headerChar + '_-*/=1'


    when:
    def simpleKeyPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, simpleKeyHeader)
    def multiKeyPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, multiKeyHeader)

    then:
    simpleKeyPT.headerValue(HeaderType.W3C) == simpleKeyHeader
    multiKeyPT.headerValue(HeaderType.W3C) == multiKeyHeader
    // we're not using any dd members in the tests
    simpleKeyPT.createTagMap() == [:]
    multiKeyPT.createTagMap() == [:]

    where:
    headerChar << ('a'..'z') + ('0'..'9')
  }

  def "validate tracestate header invalid key contents '#headerChar'"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)
    def lcAlpha = toLcAlpha(headerChar)
    def simpleKeyHeader = lcAlpha + headerChar + '_-*/=1'
    def multiKeyHeader = lcAlpha + headerChar + '@' + lcAlpha + headerChar + '_-*/=1'


    when:
    def simpleKeyPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, simpleKeyHeader)
    def multiKeyPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, multiKeyHeader)

    then:
    simpleKeyPT.headerValue(HeaderType.W3C) == null
    multiKeyPT.headerValue(HeaderType.W3C) == null
    // we're not using any dd members in the tests
    simpleKeyPT.createTagMap() == [:]
    multiKeyPT.createTagMap() == [:]

    where:
    headerChar << (' '..'ÿ') - (('a'..'z') + ('0'..'9') + '_' + '-' + '*' + '/')
  }


  def "validate tracestate header valid value contents '#valueChar'"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)
    def lcAlpha = toLcAlpha(valueChar)
    def mostlyOkHeader = lcAlpha + '=' + valueChar
    def alwaysOkHeader = lcAlpha + '=' + lcAlpha + valueChar + lcAlpha

    when:
    def mostlyOkPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, mostlyOkHeader)
    def alwaysOkPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, alwaysOkHeader)

    then:
    if (valueChar == ' ') {
      assert mostlyOkPT.headerValue(HeaderType.W3C) == null
    } else {
      assert mostlyOkPT.headerValue(HeaderType.W3C) == mostlyOkHeader
    }
    alwaysOkPT.headerValue(HeaderType.W3C) == alwaysOkHeader
    // we're not using any dd members in the tests
    mostlyOkPT.createTagMap() == [:]
    alwaysOkPT.createTagMap() == [:]

    where:
    valueChar << (' '..'~') - [',', '=']
  }

  def "validate tracestate header invalid value contents '#valueChar'"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)
    def lcAlpha = toLcAlpha(valueChar)
    def alwaysBadHeader = lcAlpha + '=' + lcAlpha + valueChar + lcAlpha

    when:
    def alwaysBadPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, alwaysBadHeader)

    then:
    alwaysBadPT.headerValue(HeaderType.W3C) == null
    // we're not using any dd members in the tests
    alwaysBadPT.createTagMap() == [:]

    where:
    valueChar << (' '..'ÿ') - ((' '..'~') - [',', '='])
  }

  def "validate tracestate header number of members #memberCount without Datadog member"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)
    def header = (1..memberCount).collect { "k$it=v$it" }.join(',')

    when:
    def headerPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, header)

    then:
    if (memberCount <= 32) {
      assert headerPT.headerValue(HeaderType.W3C) == header
    } else {
      assert headerPT.headerValue(HeaderType.W3C) == null
    }
    // we're not using any dd members in the tests
    headerPT.createTagMap() == [:]

    where:
    memberCount << (1..37) // some arbitrary number larger than 32
  }

  def "validate tracestate header number of members #memberCount with Datadog member"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)
    def header = 'dd=s:1,'+(1..memberCount).collect { "k$it=v$it" }.join(',')

    when:
    def headerPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, header)

    then:
    if (memberCount + 1 <= 32) {
      assert headerPT.headerValue(HeaderType.W3C) == header
    } else {
      assert headerPT.headerValue(HeaderType.W3C) == null
    }
    // we're not using any dd members in the tests
    headerPT.createTagMap() == [:]

    where:
    memberCount << (1..37) // some arbitrary number larger than 32
  }

  def "validate tracestate header number of members #memberCount when propagating original tracestate"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)
    def header = (1..memberCount).collect { "k$it=v$it" }.join(',')
    def expectedHeader = 'dd=t.dm:-4,' + (
      memberCount > 32 ?
      '' :
      (1..Math.min(memberCount, 31)).collect { "k$it=v$it" }.join(','))

    when:
    def datadogHeaderPT = propagationTagsFactory.fromHeaderValue(HeaderType.DATADOG, '_dd.p.dm=-4')
    def headerPT = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, header)
    datadogHeaderPT.updateW3CTracestate(headerPT.getW3CTracestate())

    then:
    if (memberCount <= 32) {
      assert datadogHeaderPT.headerValue(HeaderType.W3C) == expectedHeader // 'dd=t.dm:-4,' + header
    } else {
      assert datadogHeaderPT.headerValue(HeaderType.W3C) == 'dd=t.dm:-4'
    }
    datadogHeaderPT.createTagMap() == ['_dd.p.dm':'-4']

    where:
    memberCount << (1..37) // some arbitrary number larger than 32
  }

  def "create propagation tags from header value #headerValue"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)

    when:
    def propagationTags = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, headerValue)

    then:
    propagationTags.headerValue(HeaderType.W3C) == expectedHeaderValue
    propagationTags.createTagMap() == tags

    where:
    headerValue                                                            | expectedHeaderValue                                  | tags
    null                                                                   | null                                                 | [:]
    ''                                                                     | null                                                 | [:]
    'dd=s:0;t.dm:934086a686-4'                                             | 'dd=s:0;t.dm:934086a686-4'                           | ['_dd.p.dm': '934086a686-4']
    'dd=s:0;t.ts:02'                                                       | 'dd=s:0;t.ts:02'                                     | ['_dd.p.ts': '02']
    'dd=s:0;t.ts:00'                                                       | 'dd=s:0'                                             | [:]
    'dd=s:0;t.dm:934086a686-4;t.ts:02'                                     | 'dd=s:0;t.dm:934086a686-4;t.ts:02'                   | ['_dd.p.dm': '934086a686-4', '_dd.p.ts': '02']
    'other=whatever,dd=s:0;t.dm:934086a686-4'                              | 'dd=s:0;t.dm:934086a686-4,other=whatever'            | ['_dd.p.dm': '934086a686-4']
    'dd=s:0;t.dm:934086a687-3,other=whatever'                              | 'dd=s:0;t.dm:934086a687-3,other=whatever'            | ['_dd.p.dm': '934086a687-3']
    'some=thing,dd=s:0;t.dm:934086a687-3,other=whatever'                   | 'dd=s:0;t.dm:934086a687-3,some=thing,other=whatever' | ['_dd.p.dm': '934086a687-3']
    'some=thing,other=whatever'                                            | 'some=thing,other=whatever'                          | [:]
    'dd=s:0;o:some;t.dm:934086a686-4'                                      | 'dd=s:0;o:some;t.dm:934086a686-4'                    | ['_dd.p.dm': '934086a686-4']
    'dd=s:0;x:unknown;t.dm:934086a686-4'                                   | 'dd=s:0;t.dm:934086a686-4;x:unknown'                 | ['_dd.p.dm': '934086a686-4']
    'other=whatever,dd=s:0;x:unknown;t.dm:934086a686-4'                    | 'dd=s:0;t.dm:934086a686-4;x:unknown,other=whatever'  | ['_dd.p.dm': '934086a686-4']
    'other=whatever,dd=xyz:unknown;t.dm:934086a686-4'                      | 'dd=t.dm:934086a686-4;xyz:unknown,other=whatever'    | ['_dd.p.dm': '934086a686-4']
    'other=whatever,dd=t.dm:934086a686-4;xyz:unknown  '                    | 'dd=t.dm:934086a686-4;xyz:unknown,other=whatever'    | ['_dd.p.dm': '934086a686-4']
    '\tsome=thing \t , dd=s:0;t.dm:934086a687-3\t\t,  other=whatever\t\t ' | 'dd=s:0;t.dm:934086a687-3,some=thing,other=whatever' | ['_dd.p.dm': '934086a687-3']
    'dd=s:0;t.a:b;t.x:y'                                                   | 'dd=s:0;t.a:b;t.x:y'                                 | ['_dd.p.a': 'b', '_dd.p.x': 'y']
    'dd=s:0;t.a:b;t.x:y \t'                                                | 'dd=s:0;t.a:b;t.x:y'                                 | ['_dd.p.a': 'b', '_dd.p.x': 'y']
    'dd=s:0;t.a:b ;t.x:y \t'                                               | 'dd=s:0;t.a:b ;t.x:y'                                | ['_dd.p.a': 'b ', '_dd.p.x': 'y']
    'dd=s:0;t.a:b \t;t.x:y \t'                                             | null                                                 | [:]
    'dd=s:0;t.tid:123456789abcdef0'                                        | 'dd=s:0;t.tid:123456789abcdef0'                      | ['_dd.p.tid': '123456789abcdef0']
    "dd=t.tid:"                                                            | null                                                 | [:] // invalid tid tag value: empty value
    "dd=t.tid:" + "1" * 1                                                  | null                                                 | ['_dd.propagation_error': 'malformed_tid 1'] // invalid tid tag value: invalid length
    "dd=t.tid:" + "1" * 15                                                 | null                                                 | ['_dd.propagation_error': 'malformed_tid 111111111111111'] // invalid tid tag value: invalid length
    "dd=t.tid:" + "1" * 17                                                 | null                                                 | ['_dd.propagation_error': 'malformed_tid 11111111111111111'] // invalid tid tag value: invalid length
    "dd=t.tid:123456789ABCDEF0"                                            | null                                                 | ['_dd.propagation_error': 'malformed_tid 123456789ABCDEF0'] // invalid tid tag value: upper-case characters
    "dd=t.tid:123456789abcdefg"                                            | null                                                 | ['_dd.propagation_error': 'malformed_tid 123456789abcdefg'] // invalid tid tag value: non-hexadecimal characters
    "dd=t.tid:-123456789abcdef"                                            | null                                                 | ['_dd.propagation_error': 'malformed_tid -123456789abcdef'] // invalid tid tag value: non-hexadecimal characters
  }

  def "w3c propagation tags should translate to datadog tags #headerValue"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)

    when:
    def propagationTags = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, headerValue)

    then:
    propagationTags.headerValue(HeaderType.DATADOG) == expectedHeaderValue
    propagationTags.createTagMap() == tags

    where:
    headerValue                                                  | expectedHeaderValue                                | tags
    'dd=s:0;t.dm:934086a686-4'                                   | '_dd.p.dm=934086a686-4'                            | ['_dd.p.dm': '934086a686-4']
    'other=whatever,dd=s:0;t.dm:934086a686-4;t.f:w00t~~'         | '_dd.p.dm=934086a686-4,_dd.p.f=w00t=='             | ['_dd.p.dm': '934086a686-4', '_dd.p.f': 'w00t==']
    'dd=s:0;t.ts:02'                                             | '_dd.p.ts=02'                                      | ['_dd.p.ts': '02']
    'dd=s:0;t.ts:00'                                             | null                                               | [:]
    'dd=s:0;t.ts:0'                                              | null                                               | [:]
    'dd=s:0;t.ts:invalid'                                        | null                                               | [:]
    'other=whatever,dd=s:0;t.dm:934086a686-4;t.f:w00t~~;t.ts:02' | '_dd.p.dm=934086a686-4,_dd.p.ts=02,_dd.p.f=w00t==' | ['_dd.p.dm': '934086a686-4', '_dd.p.f': 'w00t==', '_dd.p.ts': '02']
    'some=thing,other=whatever'                                  | null                                               | [:]
  }

  def "propagation tags should be updated by sampling and origin #headerValue #priority #mechanism #origin"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)

    when:
    def propagationTags = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, headerValue)

    then:
    propagationTags.headerValue(HeaderType.W3C) != expectedHeaderValue

    when:
    propagationTags.updateTraceSamplingPriority(priority, mechanism)
    propagationTags.updateTraceOrigin(origin)

    then:
    propagationTags.headerValue(HeaderType.W3C) == expectedHeaderValue
    propagationTags.createTagMap() == tags

    where:
    headerValue                       | priority                      | mechanism                           | origin  | expectedHeaderValue                | tags
    'dd=s:0;o:some;t.dm:934086a686-4' | PrioritySampling.SAMPLER_KEEP | SamplingMechanism.DEFAULT           | "other" | 'dd=s:0;o:other;t.dm:934086a686-4' | ['_dd.p.dm': '934086a686-4']
    'dd=s:0;o:some;x:unknown'         | PrioritySampling.USER_KEEP    | SamplingMechanism.LOCAL_USER_RULE   | "same"  | 'dd=s:2;o:same;t.dm:-3;x:unknown'  | ['_dd.p.dm': '-3']
    'dd=s:0;o:some;x:unknown'         | PrioritySampling.USER_DROP    | SamplingMechanism.MANUAL            | null    | 'dd=s:-1;x:unknown'                | [:]
    'dd=s:0;o:some;t.dm:934086a686-4' | PrioritySampling.SAMPLER_KEEP | SamplingMechanism.EXTERNAL_OVERRIDE | "other" | 'dd=s:1;o:other;t.dm:-0'           | ['_dd.p.dm': '-0']
    'dd=s:1;o:some;t.dm:934086a686-4' | PrioritySampling.SAMPLER_DROP | SamplingMechanism.EXTERNAL_OVERRIDE | "other" | 'dd=s:0;o:other'                   | [:]
  }

  def "propagation tags should be updated by product trace source propagation #product"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)

    when:
    def propagationTags = propagationTagsFactory.fromHeaderValue(HeaderType.W3C, headerValue)

    then:
    propagationTags.headerValue(HeaderType.W3C) != expectedHeaderValue

    when:
    propagationTags.addTraceSource(product)

    then:
    propagationTags.headerValue(HeaderType.W3C) == expectedHeaderValue
    propagationTags.createTagMap() == tags

    where:
    headerValue            | product                | expectedHeaderValue    | tags
    'dd=x:unknown'         | ProductTraceSource.ASM | 'dd=t.ts:02;x:unknown' | ['_dd.p.ts': '02']
    'dd=t.ts:02;x:unknown' | ProductTraceSource.DBM | 'dd=t.ts:12;x:unknown' | ['_dd.p.ts': '12']
    "dd=t.ts:00"           | ProductTraceSource.ASM | 'dd=t.ts:02'           | ["_dd.p.ts": "02"]
    "dd=t.ts:FFC00000"     | ProductTraceSource.ASM | 'dd=t.ts:02'           | ["_dd.p.ts": "02"]
  }

  static private String toLcAlpha(String cs) {
    // Argh groovy and characters
    char c = cs
    char a = 'a'
    char z = 'z'
    "${Character.valueOf((a + (Math.abs(c - a) % (z - a))) as char)}"
  }
}
