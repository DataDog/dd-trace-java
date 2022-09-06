package datadog.trace.core.propagation

import datadog.trace.api.Config
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*

class DatadogTagsTest extends DDCoreSpecification {

  def createDatadogTagsFromHeaderValue() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def datadogTagsFactory = DatadogTags.factory(config)

    when:
    def datadogTags = datadogTagsFactory.fromHeaderValue(headerValue)

    then:
    datadogTags.headerValue() == expectedHeaderValue
    datadogTags.createTagMap() == tags

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
    "_dd.p.a=b=1=2"                                                                                                              | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tag key containing equality
    "_dd.p.1ö2=value"                                                                                                            | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tag key containing not allowed char
    "_dd.p.ab=1=2"                                                                                                               | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tag value containing equality
    "_dd.p.ab=1ô2"                                                                                                               | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid tag value containing not allowed char
    "_dd.p.dm=934086A686-4"                                                                                                      | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value contains invalid char
    "_dd.p.dm=934086a66-4"                                                                                                       | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value shorter than 10 chars
    "_dd.p.dm=934086a6653-4"                                                                                                     | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value longer than 10 chars
    "_dd.p.dm=934086a66534"                                                                                                      | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value missing separator
    "_dd.p.dm=934086a665-"                                                                                                       | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value missing sampling mechanism
    "_dd.p.dm=934086a665-a"                                                                                                      | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value sampling mechanism contains invalid char
    "_dd.p.dm=934086a665-12b"                                                                                                    | null                                       | ["_dd.propagation_error": "decoding_error"] // invalid dm tag value sampling mechanism contains invalid char
  }

  def updateDatadogTagsSamplingMechanism() {
    setup:
    def config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512
    def datadogTagsFactory = DatadogTags.factory(config)
    def datadogTags = datadogTagsFactory.fromHeaderValue(originalTagSet)

    when:
    datadogTags.updateTraceSamplingPriority(priority, mechanism, "service-1")

    then:
    datadogTags.headerValue() == expectedHeaderValue
    datadogTags.createTagMap() == tags

    where:
    originalTagSet                                              | priority     | mechanism  | expectedHeaderValue                                         | tags
    // keep the existing dm tag as is
    "_dd.p.dm=934086a686-4"                                     | UNSET        | UNKNOWN    | "_dd.p.dm=934086a686-4"                                     | ["_dd.p.dm": "934086a686-4"]
    "_dd.p.dm=934086a686-3"                                     | SAMPLER_KEEP | AGENT_RATE | "_dd.p.dm=934086a686-3"                                     | ["_dd.p.dm": "934086a686-3"]
    "_dd.p.dm=93485302ab-1"                                     | SAMPLER_KEEP | APPSEC     | "_dd.p.dm=93485302ab-1"                                     | ["_dd.p.dm": "93485302ab-1"]
    "_dd.p.dm=934086a686-4,_dd.p.anytag=value"                  | SAMPLER_KEEP | AGENT_RATE | "_dd.p.dm=934086a686-4,_dd.p.anytag=value"                  | ["_dd.p.dm": "934086a686-4", "_dd.p.anytag": "value"]
    "_dd.p.dm=93485302ab-2,_dd.p.anytag=value"                  | SAMPLER_KEEP | APPSEC     | "_dd.p.dm=93485302ab-2,_dd.p.anytag=value"                  | ["_dd.p.dm": "93485302ab-2", "_dd.p.anytag": "value"]
    "_dd.p.anytag=value,_dd.p.dm=934086a686-4"                  | SAMPLER_KEEP | AGENT_RATE | "_dd.p.anytag=value,_dd.p.dm=934086a686-4"                  | ["_dd.p.anytag": "value", "_dd.p.dm": "934086a686-4"]
    "_dd.p.anytag=value,_dd.p.dm=93485302ab-2"                  | SAMPLER_KEEP | APPSEC     | "_dd.p.anytag=value,_dd.p.dm=93485302ab-2"                  | ["_dd.p.anytag": "value", "_dd.p.dm": "93485302ab-2"]
    "_dd.p.anytag=value,_dd.p.dm=934086a686-4,_dd.p.atag=value" | SAMPLER_KEEP | AGENT_RATE | "_dd.p.anytag=value,_dd.p.dm=934086a686-4,_dd.p.atag=value" | ["_dd.p.anytag": "value", "_dd.p.dm": "934086a686-4", "_dd.p.atag": "value"]
    "_dd.p.anytag=value,_dd.p.dm=93485302ab-2,_dd.p.atag=value" | SAMPLER_KEEP | APPSEC     | "_dd.p.anytag=value,_dd.p.dm=93485302ab-2,_dd.p.atag=value" | ["_dd.p.anytag": "value", "_dd.p.dm": "93485302ab-2", "_dd.p.atag": "value"]
    "_dd.p.dm=93485302ab-2"                                     | USER_DROP    | MANUAL     | "_dd.p.dm=93485302ab-2"                                     | ["_dd.p.dm": "93485302ab-2"]
    "_dd.p.anytag=value,_dd.p.dm=93485302ab-2"                  | SAMPLER_DROP | MANUAL     | "_dd.p.anytag=value,_dd.p.dm=93485302ab-2"                  | ["_dd.p.anytag": "value", "_dd.p.dm": "93485302ab-2"]
    "_dd.p.dm=93485302ab-2,_dd.p.anytag=value"                  | USER_DROP    | MANUAL     | "_dd.p.dm=93485302ab-2,_dd.p.anytag=value"                  | ["_dd.p.dm": "93485302ab-2", "_dd.p.anytag": "value"]
    "_dd.p.atag=value,_dd.p.dm=93485302ab-2,_dd.p.anytag=value" | USER_DROP    | MANUAL     | "_dd.p.atag=value,_dd.p.dm=93485302ab-2,_dd.p.anytag=value" | ["_dd.p.atag": "value", "_dd.p.dm": "93485302ab-2", "_dd.p.anytag": "value"]
    // propagate sampling mechanism only
    ""                                                          | SAMPLER_KEEP | DEFAULT    | "_dd.p.dm=-0"                                               | ["_dd.p.dm": "-0"]
    ""                                                          | SAMPLER_KEEP | AGENT_RATE | "_dd.p.dm=-1"                                               | ["_dd.p.dm": "-1"]
    "_dd.p.anytag=value"                                        | USER_KEEP    | MANUAL     | "_dd.p.anytag=value,_dd.p.dm=-4"                            | ["_dd.p.anytag": "value", "_dd.p.dm": "-4"]
    "_dd.p.anytag=value,_dd.p.atag=value"                       | SAMPLER_DROP | MANUAL     | "_dd.p.anytag=value,_dd.p.atag=value"                       | ["_dd.p.anytag": "value", "_dd.p.atag": "value"]
    // do not set the dm tags when mechanism is UNKNOWN
    "_dd.p.anytag=123"                                          | SAMPLER_KEEP | UNKNOWN    | "_dd.p.anytag=123"                                          | ["_dd.p.anytag": "123"]
    // invalid input
    ",_dd.p.dm=Value"                                           | SAMPLER_KEEP | AGENT_RATE | null                                                        | ["_dd.propagation_error": "decoding_error"]
  }

  def extractionLimitExceeded() {
    setup:
    def tags = "_dd.p.anytag=value"
    def limit = tags.length() - 1
    def datadogTags = DatadogTags.factory(limit).fromHeaderValue(tags)

    when:
    datadogTags.updateTraceSamplingPriority(USER_KEEP, MANUAL, "service-name")

    then:
    datadogTags.headerValue() == null
    datadogTags.createTagMap() == ["_dd.propagation_error": "extract_max_size"]
  }

  def injectionLimitExceeded() {
    setup:
    def tags = "_dd.p.anytag=value"
    def limit = tags.length()
    def datadogTags = DatadogTags.factory(limit).fromHeaderValue(tags)

    when:
    datadogTags.updateTraceSamplingPriority(USER_KEEP, MANUAL, "service-name")

    then:
    datadogTags.headerValue() == null
    datadogTags.createTagMap() == ["_dd.propagation_error": "inject_max_size"]
  }

  def injectionLimitExceededLimit0() {
    setup:
    def datadogTags = DatadogTags.factory(0).fromHeaderValue("")

    when:
    datadogTags.updateTraceSamplingPriority(USER_KEEP, MANUAL, "service-name")

    then:
    datadogTags.headerValue() == null
    datadogTags.createTagMap() == ["_dd.propagation_error": "disabled"]
  }
}
