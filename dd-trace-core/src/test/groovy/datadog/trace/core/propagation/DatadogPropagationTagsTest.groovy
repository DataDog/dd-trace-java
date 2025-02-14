package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.api.ProductTraceSource
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*

class DatadogPropagationTagsTest extends DDCoreSpecification {

  def "create propagation tags from header value '#headerValue'"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)

    when:
    def propagationTags = propagationTagsFactory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, headerValue)

    then:
    propagationTags.headerValue(PropagationTags.HeaderType.DATADOG) == expectedHeaderValue
    propagationTags.createTagMap() == tags

    where:
    headerValue                                                                                                                  | expectedHeaderValue                        | tags
    null                                                                                                                         | null                                       | [:]
    ""                                                                                                                           | null                                       | [:]
    "_dd.p.dm=934086a686-4"                                                                                                      | "_dd.p.dm=934086a686-4"                    | ["_dd.p.dm": "934086a686-4"]
    "_dd.p.dm=934086a686-10"                                                                                                     | "_dd.p.dm=934086a686-10"                   | ["_dd.p.dm": "934086a686-10"]
    "_dd.p.dm=934086a686-102"                                                                                                    | "_dd.p.dm=934086a686-102"                  | ["_dd.p.dm": "934086a686-102"]
    "_dd.p.dm=-1"                                                                                                                | "_dd.p.dm=-1"                              | ["_dd.p.dm": "-1"]
    "_dd.p.anytag=value"                                                                                                         | "_dd.p.anytag=value"                       | ["_dd.p.anytag": "value"]
    // drop _dd.p.upstream_services and any other but _dd.p.*
    "_dd.b.somekey=value"                                                                                                        | null                                       | [:]
    "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1"                                                                            | null                                       | [:]
    "_dd.p.dm=934086a686-4,_dd.p.anytag=value"                                                                                   | "_dd.p.dm=934086a686-4,_dd.p.anytag=value" | ["_dd.p.dm": "934086a686-4", "_dd.p.anytag": "value"]
    "_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value"                                   | "_dd.p.dm=934086a686-4,_dd.p.anytag=value" | ["_dd.p.dm": "934086a686-4", "_dd.p.anytag": "value"]
    "_dd.b.keyonly=value,_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value"               | "_dd.p.dm=934086a686-4,_dd.p.anytag=value" | ["_dd.p.dm": "934086a686-4", "_dd.p.anytag": "value"]
    // valid tag value containing spaces
    "_dd.p.ab=1 2 3"                                                                                                             | "_dd.p.ab=1 2 3"                           | ["_dd.p.ab": "1 2 3"]
    "_dd.p.ab= 123 "                                                                                                             | "_dd.p.ab= 123 "                           | ["_dd.p.ab": " 123 "]
    // decoding error
    "_dd.p.keyonly"                                                                                                              | null                                       | ["_dd.propagation_error": "decoding_error"]
    ",_dd.p.dm=Value"                                                                                                            | null                                       | ["_dd.propagation_error": "decoding_error"]
    ","                                                                                                                          | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid comma only tagSet
    "_dd.b.somekey=value,_dd.p.dm=934086a686-4,_dd.p.keyonly,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value" | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid _dd.p.keyonly tag without a value
    "_dd.p.keyonly,_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value"                     | null                                       | ["_dd.propagation_error": "decoding_error"] //
    ",_dd.p.dm=934086a686-4,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value"                                  | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tagSet with a leading comma
    "_dd.p.dm=934086a686-4,,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value"                                  | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tagSet with two commas in a row
    "_dd.p.dm=934086a686-4, ,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1,_dd.p.anytag=value"                                 | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tagSet with a space instead of a tag
    // do not validate tag value if the tag is dropped
    "_dd.p.upstream_services=bmV1dHJvbg==|0|1|0.2253"                                                                            | null                                       | [:]
    " _dd.p.ab=123"                                                                                                              | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tag set containing leading space
    "_dd.p.a b=123"                                                                                                              | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tag key containing space
    "_dd.p.ab =123"                                                                                                              | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tag key containing space
    "_dd.p. ab=123"                                                                                                              | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tag key containing space
    "_dd.p.a=b=1=2"                                                                                                              | "_dd.p.a=b=1=2"                            | ["_dd.p.a": "b=1=2"]
    "_dd.p.1ö2=value"                                                                                                            | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tag key containing not allowed char
    "_dd.p.ab=1=2"                                                                                                               | "_dd.p.ab=1=2"                             | ["_dd.p.ab": "1=2"]
    "_dd.p.ab=1ô2"                                                                                                               | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tag value containing not allowed char
    "_dd.p.dm=934086A686-4"                                                                                                      | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value contains invalid char
    "_dd.p.dm=934086a66-4"                                                                                                       | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value shorter than 10 chars
    "_dd.p.dm=934086a6653-4"                                                                                                     | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value longer than 10 chars
    "_dd.p.dm=934086a66534"                                                                                                      | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value missing separator
    "_dd.p.dm=934086a665-"                                                                                                       | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value missing sampling mechanism
    "_dd.p.dm=934086a665-a"                                                                                                      | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value sampling mechanism contains invalid char
    "_dd.p.dm=934086a665-12b"                                                                                                    | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value sampling mechanism contains invalid char
    "_dd.p.tid="                                                                                                                 | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tid tag value: empty value
    "_dd.p.tid=" + "1" * 1                                                                                                       | null                                       | ["_dd.propagation_error": "malformed_tid 1"] // invalid tid tag value: invalid length
    "_dd.p.tid=" + "1" * 15                                                                                                      | null                                       | ["_dd.propagation_error": "malformed_tid 111111111111111"] // invalid tid tag value: invalid length
    "_dd.p.tid=" + "1" * 17                                                                                                      | null                                       | ["_dd.propagation_error": "malformed_tid 11111111111111111"] // invalid tid tag value: invalid length
    "_dd.p.tid=123456789ABCDEF0"                                                                                                 | null                                       | ["_dd.propagation_error": "malformed_tid 123456789ABCDEF0"] // invalid tid tag value: upper-case characters
    "_dd.p.tid=123456789abcdefg"                                                                                                 | null                                       | ["_dd.propagation_error": "malformed_tid 123456789abcdefg"] // invalid tid tag value: non-hexadecimal characters
    "_dd.p.tid=-123456789abcdef"                                                                                                 | null                                       | ["_dd.propagation_error": "malformed_tid -123456789abcdef"] // invalid tid tag value: non-hexadecimal characters
    "_dd.p.ts=02"                                                                                                                | "_dd.p.ts=02"                              | ["_dd.p.ts": "02"]
    "_dd.p.ts=00"                                                                                                                | null                                       | [:]
    "_dd.p.ts=foo"                                                                                                               | null                                       | ["_dd.propagation_error": "decoding_error"]
  }

  def "datadog propagation tags should translate to w3c tags #headerValue"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)

    when:
    def propagationTags = propagationTagsFactory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, headerValue)

    then:
    propagationTags.headerValue(PropagationTags.HeaderType.W3C) == expectedHeaderValue
    propagationTags.createTagMap() == tags

    where:
    headerValue                            | expectedHeaderValue               | tags
    '_dd.p.dm=934086a686-4'                | 'dd=t.dm:934086a686-4'            | ['_dd.p.dm': '934086a686-4']
    '_dd.p.dm=934086a686-4,_dd.p.f=w00t==' | 'dd=t.dm:934086a686-4;t.f:w00t~~' | ['_dd.p.dm': '934086a686-4', '_dd.p.f': 'w00t==']
    '_dd.p.dm=934086a686-4,_dd.p.appsec=1' | 'dd=t.dm:934086a686-4;t.appsec:1' | ['_dd.p.dm': '934086a686-4', '_dd.p.appsec': '1']
  }

  def "update propagation tags sampling mechanism #originalTagSet"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)
    def propagationTags = propagationTagsFactory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, originalTagSet)

    when:
    propagationTags.updateTraceSamplingPriority(priority, mechanism)

    then:
    propagationTags.headerValue(PropagationTags.HeaderType.DATADOG) == expectedHeaderValue
    propagationTags.createTagMap() == tags

    where:
    originalTagSet                                              | priority     | mechanism  | expectedHeaderValue                                         | tags
    // keep the existing dm tag as is
    "_dd.p.dm=934086a686-4"                                     | UNSET        | UNKNOWN    | "_dd.p.dm=934086a686-4"                                     | ["_dd.p.dm": "934086a686-4"]
    "_dd.p.dm=934086a686-3"                                     | SAMPLER_KEEP | AGENT_RATE | "_dd.p.dm=934086a686-3"                                     | ["_dd.p.dm": "934086a686-3"]
    "_dd.p.dm=93485302ab-1"                                     | SAMPLER_KEEP | APPSEC     | "_dd.p.dm=93485302ab-1"                                     | ["_dd.p.dm": "93485302ab-1"]
    "_dd.p.dm=934086a686-4,_dd.p.anytag=value"                  | SAMPLER_KEEP | AGENT_RATE | "_dd.p.dm=934086a686-4,_dd.p.anytag=value"                  | ["_dd.p.dm": "934086a686-4", "_dd.p.anytag": "value"]
    "_dd.p.dm=93485302ab-2,_dd.p.anytag=value"                  | SAMPLER_KEEP | APPSEC     | "_dd.p.dm=93485302ab-2,_dd.p.anytag=value"                  | ["_dd.p.dm": "93485302ab-2", "_dd.p.anytag": "value"]
    "_dd.p.anytag=value,_dd.p.dm=934086a686-4"                  | SAMPLER_KEEP | AGENT_RATE | "_dd.p.dm=934086a686-4,_dd.p.anytag=value"                  | ["_dd.p.anytag": "value", "_dd.p.dm": "934086a686-4"]
    "_dd.p.anytag=value,_dd.p.dm=93485302ab-2"                  | SAMPLER_KEEP | APPSEC     | "_dd.p.dm=93485302ab-2,_dd.p.anytag=value"                  | ["_dd.p.anytag": "value", "_dd.p.dm": "93485302ab-2"]
    "_dd.p.anytag=value,_dd.p.dm=934086a686-4,_dd.p.atag=value" | SAMPLER_KEEP | AGENT_RATE | "_dd.p.dm=934086a686-4,_dd.p.anytag=value,_dd.p.atag=value" | ["_dd.p.anytag": "value", "_dd.p.dm": "934086a686-4", "_dd.p.atag": "value"]
    "_dd.p.anytag=value,_dd.p.dm=93485302ab-2,_dd.p.atag=value" | SAMPLER_KEEP | APPSEC     | "_dd.p.dm=93485302ab-2,_dd.p.anytag=value,_dd.p.atag=value" | ["_dd.p.anytag": "value", "_dd.p.dm": "93485302ab-2", "_dd.p.atag": "value"]
    "_dd.p.dm=93485302ab-2"                                     | USER_DROP    | MANUAL     | "_dd.p.dm=93485302ab-2"                                     | ["_dd.p.dm": "93485302ab-2"]
    "_dd.p.anytag=value,_dd.p.dm=93485302ab-2"                  | SAMPLER_DROP | MANUAL     | "_dd.p.dm=93485302ab-2,_dd.p.anytag=value"                  | ["_dd.p.anytag": "value", "_dd.p.dm": "93485302ab-2"]
    "_dd.p.dm=93485302ab-2,_dd.p.anytag=value"                  | USER_DROP    | MANUAL     | "_dd.p.dm=93485302ab-2,_dd.p.anytag=value"                  | ["_dd.p.dm": "93485302ab-2", "_dd.p.anytag": "value"]
    "_dd.p.atag=value,_dd.p.dm=93485302ab-2,_dd.p.anytag=value" | USER_DROP    | MANUAL     | "_dd.p.dm=93485302ab-2,_dd.p.atag=value,_dd.p.anytag=value" | ["_dd.p.atag": "value", "_dd.p.dm": "93485302ab-2", "_dd.p.anytag": "value"]
    // propagate sampling mechanism only
    ""                                                          | SAMPLER_KEEP | DEFAULT    | "_dd.p.dm=-0"                                               | ["_dd.p.dm": "-0"]
    ""                                                          | SAMPLER_KEEP | AGENT_RATE | "_dd.p.dm=-1"                                               | ["_dd.p.dm": "-1"]
    "_dd.p.anytag=value"                                        | USER_KEEP    | MANUAL     | "_dd.p.dm=-4,_dd.p.anytag=value"                            | ["_dd.p.anytag": "value", "_dd.p.dm": "-4"]
    "_dd.p.anytag=value,_dd.p.atag=value"                       | SAMPLER_DROP | MANUAL     | "_dd.p.anytag=value,_dd.p.atag=value"                       | ["_dd.p.anytag": "value", "_dd.p.atag": "value"]
    // do not set the dm tags when mechanism is UNKNOWN
    "_dd.p.anytag=123"                                          | SAMPLER_KEEP | UNKNOWN    | "_dd.p.anytag=123"                                          | ["_dd.p.anytag": "123"]
    // invalid input
    ",_dd.p.dm=Value"                                           | SAMPLER_KEEP | AGENT_RATE | "_dd.p.dm=-1"                                               | ["_dd.propagation_error": "decoding_error", "_dd.p.dm": "-1"]
  }

  def "update propagation tags trace source propagation #originalTagSet"() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def propagationTagsFactory = PropagationTags.factory(config)
    def propagationTags = propagationTagsFactory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, originalTagSet)

    when:
    propagationTags.addTraceSource(product)

    then:
    propagationTags.headerValue(PropagationTags.HeaderType.DATADOG) == expectedHeaderValue
    propagationTags.createTagMap() == tags

    where:
    originalTagSet      | product                  | expectedHeaderValue | tags
    // keep the existing dm tag as is
    ""                  | ProductTraceSource.ASM   | "_dd.p.ts=02"       | ["_dd.p.ts": "02"]
    "_dd.p.ts=00"       | ProductTraceSource.ASM   | "_dd.p.ts=02"       | ["_dd.p.ts": "02"]
    "_dd.p.ts=FFC00000" | ProductTraceSource.ASM   | "_dd.p.ts=02"       | ["_dd.p.ts": "02"]
    "_dd.p.ts=02"       | ProductTraceSource.DBM   | "_dd.p.ts=12"       | ["_dd.p.ts": "12"]
    //Invalid input
    "_dd.p.ts="         | ProductTraceSource.UNSET | null                | ["_dd.propagation_error": "decoding_error"]
    "_dd.p.ts=0"        | ProductTraceSource.UNSET | null                | ["_dd.propagation_error": "decoding_error"]
    "_dd.p.ts=0G"       | ProductTraceSource.UNSET | null                | ["_dd.propagation_error": "decoding_error"]
    "_dd.p.ts=GG"       | ProductTraceSource.UNSET | null                | ["_dd.propagation_error": "decoding_error"]
    "_dd.p.ts=foo"      | ProductTraceSource.UNSET | null                | ["_dd.propagation_error": "decoding_error"]
    "_dd.p.ts=000000002" | ProductTraceSource.UNSET | null                | ["_dd.propagation_error": "decoding_error"]
  }

  def extractionLimitExceeded() {
    setup:
    def tags = "_dd.p.anytag=value"
    def limit = tags.length() - 1
    def propagationTags = PropagationTags.factory(limit).fromHeaderValue(PropagationTags.HeaderType.DATADOG, tags)

    when:
    propagationTags.updateTraceSamplingPriority(USER_KEEP, MANUAL)

    then:
    propagationTags.headerValue(PropagationTags.HeaderType.DATADOG) == "_dd.p.dm=-4"
    propagationTags.createTagMap() == ["_dd.propagation_error": "extract_max_size", "_dd.p.dm": "-4"]
  }

  def injectionLimitExceeded() {
    setup:
    def tags = "_dd.p.anytag=value"
    def limit = tags.length()
    def propagationTags = PropagationTags.factory(limit).fromHeaderValue(PropagationTags.HeaderType.DATADOG, tags)

    when:
    propagationTags.updateTraceSamplingPriority(USER_KEEP, MANUAL)

    then:
    propagationTags.headerValue(PropagationTags.HeaderType.DATADOG) == null
    propagationTags.createTagMap() == ["_dd.propagation_error": "inject_max_size"]
  }

  def injectionLimitExceededLimit0() {
    setup:
    def propagationTags = PropagationTags.factory(0).fromHeaderValue(PropagationTags.HeaderType.DATADOG, "")

    when:
    propagationTags.updateTraceSamplingPriority(USER_KEEP, MANUAL)

    then:
    propagationTags.headerValue(PropagationTags.HeaderType.DATADOG) == null
    propagationTags.createTagMap() == ["_dd.propagation_error": "disabled"]
  }
}
