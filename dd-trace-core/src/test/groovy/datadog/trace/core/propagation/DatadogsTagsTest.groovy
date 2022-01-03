package datadog.trace.core.propagation

import spock.lang.Specification

import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*

class DatadogsTagsTest extends Specification {

  def "update DatadogTags without rate"() {
    setup:
    def ddTags = DatadogTags.empty()

    when:
    ddTags.updateUpstreamServices(service, samplingPriority, samplingMechanism, rate)

    then:
    ddTags.encoded() == expected

    where:
    service     | samplingPriority | samplingMechanism | rate | expected
    "service-a" | UNSET            | UNKNOWN           | -1.0 | ""
    "service-a" | SAMPLER_DROP     | UNKNOWN           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|0|-1"
    "service-a" | SAMPLER_KEEP     | UNKNOWN           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|1|-1"
    "service-a" | USER_DROP        | UNKNOWN           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|-1|-1"
    "service-a" | USER_KEEP        | UNKNOWN           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|2|-1"

    "service-a" | SAMPLER_DROP     | DEFAULT           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|0|0"
    "service-a" | SAMPLER_KEEP     | DEFAULT           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|1|0"

    "service-a" | SAMPLER_DROP     | AGENT_RATE        | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|0|1"
    "service-a" | SAMPLER_KEEP     | AGENT_RATE        | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|1|1"

    "service-a" | SAMPLER_DROP     | REMOTE_AUTO_RATE  | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|0|2"
    "service-a" | SAMPLER_KEEP     | REMOTE_AUTO_RATE  | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|1|2"

    "service-a" | USER_DROP        | RULE              | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|-1|3"
    "service-a" | USER_KEEP        | RULE              | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|2|3"

    "service-a" | USER_DROP        | MANUAL            | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|-1|4"
    "service-a" | USER_KEEP        | MANUAL            | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|2|4"

    "service-a" | USER_KEEP        | APPSEC            | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|2|5"

    "service-a" | USER_DROP        | REMOTE_USER_RATE  | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|-1|6"
    "service-a" | USER_KEEP        | REMOTE_USER_RATE  | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|2|6"
  }

  def "update DatadogTags with rate"() {
    setup:
    def ddTags = DatadogTags.empty()

    when:
    ddTags.updateUpstreamServices(service, samplingPriority, samplingMechanism, rate)

    then:
    ddTags.encoded() == expected

    where:
    service     | samplingPriority | samplingMechanism | rate    | expected
    "service-a" | UNSET            | UNKNOWN           | 1.0     | ""
    "service-a" | SAMPLER_DROP     | UNKNOWN           | 0.0009  | "_dd.p.upstream_services=c2VydmljZS1h|0|-1|0.0009"
    "service-a" | SAMPLER_KEEP     | UNKNOWN           | 0.00009 | "_dd.p.upstream_services=c2VydmljZS1h|1|-1|0.0001"
    "service-a" | USER_DROP        | UNKNOWN           | 1.0     | "_dd.p.upstream_services=c2VydmljZS1h|-1|-1|1"
    "service-a" | USER_KEEP        | UNKNOWN           | 0.1234  | "_dd.p.upstream_services=c2VydmljZS1h|2|-1|0.1234"

    "service-a" | SAMPLER_DROP     | DEFAULT           | 1.0     | "_dd.p.upstream_services=c2VydmljZS1h|0|0|1"
    "service-a" | SAMPLER_KEEP     | DEFAULT           | 0.1234  | "_dd.p.upstream_services=c2VydmljZS1h|1|0|0.1234"

    "service-a" | SAMPLER_DROP     | AGENT_RATE        | 1.0     | "_dd.p.upstream_services=c2VydmljZS1h|0|1|1"
    "service-a" | SAMPLER_KEEP     | AGENT_RATE        | 0.1234  | "_dd.p.upstream_services=c2VydmljZS1h|1|1|0.1234"

    "service-a" | SAMPLER_DROP     | REMOTE_AUTO_RATE  | 1.0     | "_dd.p.upstream_services=c2VydmljZS1h|0|2|1"
    "service-a" | SAMPLER_KEEP     | REMOTE_AUTO_RATE  | 0.1234  | "_dd.p.upstream_services=c2VydmljZS1h|1|2|0.1234"

    "service-a" | USER_DROP        | RULE              | 1.0     | "_dd.p.upstream_services=c2VydmljZS1h|-1|3|1"
    "service-a" | USER_KEEP        | RULE              | 0.1234  | "_dd.p.upstream_services=c2VydmljZS1h|2|3|0.1234"

    "service-a" | USER_DROP        | MANUAL            | 1.0     | "_dd.p.upstream_services=c2VydmljZS1h|-1|4|1"
    "service-a" | USER_KEEP        | MANUAL            | 0.1234  | "_dd.p.upstream_services=c2VydmljZS1h|2|4|0.1234"

    "service-a" | USER_KEEP        | APPSEC            | 1.0     | "_dd.p.upstream_services=c2VydmljZS1h|2|5|1"

    "service-a" | USER_DROP        | REMOTE_USER_RATE  | 1.0     | "_dd.p.upstream_services=c2VydmljZS1h|-1|6|1"
    "service-a" | USER_KEEP        | REMOTE_USER_RATE  | 0.1234  | "_dd.p.upstream_services=c2VydmljZS1h|2|6|0.1234"
  }

  def "keep existing tags"() {
    setup:
    def ddTags = DatadogTags.create("_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|,_dd.p.hello=world")

    when:
    ddTags.updateUpstreamServices(service, samplingPriority, samplingMechanism, rate)

    then:
    ddTags.encoded() == expected

    where:
    service     | samplingPriority | samplingMechanism | rate | expected
    "service-a" | UNSET            | UNKNOWN           | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|,_dd.p.hello=world"
    "service-a" | SAMPLER_DROP     | UNKNOWN           | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|0|-1,_dd.p.hello=world"
    "service-a" | SAMPLER_KEEP     | UNKNOWN           | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|1|-1,_dd.p.hello=world"
    "service-a" | USER_DROP        | UNKNOWN           | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|-1|-1,_dd.p.hello=world"
    "service-a" | USER_KEEP        | UNKNOWN           | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|2|-1,_dd.p.hello=world"

    "service-a" | SAMPLER_DROP     | DEFAULT           | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|0|0,_dd.p.hello=world"
    "service-a" | SAMPLER_KEEP     | DEFAULT           | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|1|0,_dd.p.hello=world"

    "service-a" | SAMPLER_DROP     | AGENT_RATE        | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|0|1,_dd.p.hello=world"
    "service-a" | SAMPLER_KEEP     | AGENT_RATE        | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|1|1,_dd.p.hello=world"

    "service-a" | SAMPLER_DROP     | REMOTE_AUTO_RATE  | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|0|2,_dd.p.hello=world"
    "service-a" | SAMPLER_KEEP     | REMOTE_AUTO_RATE  | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|1|2,_dd.p.hello=world"

    "service-a" | USER_DROP        | RULE              | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|-1|3,_dd.p.hello=world"
    "service-a" | USER_KEEP        | RULE              | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|2|3,_dd.p.hello=world"

    "service-a" | USER_DROP        | MANUAL            | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|-1|4,_dd.p.hello=world"
    "service-a" | USER_KEEP        | MANUAL            | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|2|4,_dd.p.hello=world"

    "service-a" | USER_KEEP        | APPSEC            | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|2|5,_dd.p.hello=world"

    "service-a" | USER_DROP        | REMOTE_USER_RATE  | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|-1|6,_dd.p.hello=world"
    "service-a" | USER_KEEP        | REMOTE_USER_RATE  | -1.0 | "_dd.p.something=else,_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|2|6,_dd.p.hello=world"
  }

  def "keep the original upstream_services"() {
    setup:
    def ddTags = DatadogTags.create("_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|")

    when:
    ddTags.updateUpstreamServices(service, samplingPriority, samplingMechanism, rate)

    then:
    ddTags.encoded() == expected

    where:
    service     | samplingPriority | samplingMechanism | rate | expected
    "service-a" | UNSET            | UNKNOWN           | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|"
    "service-a" | SAMPLER_DROP     | UNKNOWN           | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|0|-1"
    "service-a" | SAMPLER_KEEP     | UNKNOWN           | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|1|-1"
    "service-a" | USER_DROP        | UNKNOWN           | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|-1|-1"
    "service-a" | USER_KEEP        | UNKNOWN           | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|2|-1"

    "service-a" | SAMPLER_DROP     | DEFAULT           | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|0|0"
    "service-a" | SAMPLER_KEEP     | DEFAULT           | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|1|0"

    "service-a" | SAMPLER_DROP     | AGENT_RATE        | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|0|1"
    "service-a" | SAMPLER_KEEP     | AGENT_RATE        | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|1|1"

    "service-a" | SAMPLER_DROP     | REMOTE_AUTO_RATE  | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|0|2"
    "service-a" | SAMPLER_KEEP     | REMOTE_AUTO_RATE  | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|1|2"

    "service-a" | USER_DROP        | RULE              | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|-1|3"
    "service-a" | USER_KEEP        | RULE              | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|2|3"

    "service-a" | USER_DROP        | MANUAL            | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|-1|4"
    "service-a" | USER_KEEP        | MANUAL            | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|2|4"

    "service-a" | USER_KEEP        | APPSEC            | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|2|5"

    "service-a" | USER_DROP        | REMOTE_USER_RATE  | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|-1|6"
    "service-a" | USER_KEEP        | REMOTE_USER_RATE  | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;dHJhY2Utc3RhdHMtcXVlcnk|2|4|;c2VydmljZS1h|2|6"
  }

  def "add upstream_services tag if it doesn't exist"() {
    setup:
    def ddTags = DatadogTags.create("_dd.p.hello=world")

    when:
    ddTags.updateUpstreamServices(service, samplingPriority, samplingMechanism, rate)

    then:
    ddTags.encoded() == expected

    where:
    service     | samplingPriority | samplingMechanism | rate | expected
    "service-a" | UNSET            | UNKNOWN           | -1.0 | "_dd.p.hello=world"
    "service-a" | SAMPLER_DROP     | UNKNOWN           | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|0|-1"
    "service-a" | SAMPLER_KEEP     | UNKNOWN           | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|1|-1"
    "service-a" | USER_DROP        | UNKNOWN           | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|-1|-1"
    "service-a" | USER_KEEP        | UNKNOWN           | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|2|-1"

    "service-a" | SAMPLER_DROP     | DEFAULT           | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|0|0"
    "service-a" | SAMPLER_KEEP     | DEFAULT           | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|1|0"

    "service-a" | SAMPLER_DROP     | AGENT_RATE        | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|0|1"
    "service-a" | SAMPLER_KEEP     | AGENT_RATE        | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|1|1"

    "service-a" | SAMPLER_DROP     | REMOTE_AUTO_RATE  | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|0|2"
    "service-a" | SAMPLER_KEEP     | REMOTE_AUTO_RATE  | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|1|2"

    "service-a" | USER_DROP        | RULE              | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|-1|3"
    "service-a" | USER_KEEP        | RULE              | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|2|3"

    "service-a" | USER_DROP        | MANUAL            | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|-1|4"
    "service-a" | USER_KEEP        | MANUAL            | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|2|4"

    "service-a" | USER_KEEP        | APPSEC            | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|2|5"

    "service-a" | USER_DROP        | REMOTE_USER_RATE  | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|-1|6"
    "service-a" | USER_KEEP        | REMOTE_USER_RATE  | -1.0 | "_dd.p.hello=world,_dd.p.upstream_services=c2VydmljZS1h|2|6"
  }

  def "add upstream_services tag if no datadog tags"() {
    setup:
    def ddTags = DatadogTags.empty()

    when:
    ddTags.updateUpstreamServices(service, samplingPriority, samplingMechanism, rate)

    then:
    ddTags.encoded() == expected

    where:
    service     | samplingPriority | samplingMechanism | rate | expected
    "service-a" | UNSET            | UNKNOWN           | -1.0 | ""
    "service-a" | SAMPLER_DROP     | UNKNOWN           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|0|-1"
    "service-a" | SAMPLER_KEEP     | UNKNOWN           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|1|-1"
    "service-a" | USER_DROP        | UNKNOWN           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|-1|-1"
    "service-a" | USER_KEEP        | UNKNOWN           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|2|-1"

    "service-a" | SAMPLER_DROP     | DEFAULT           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|0|0"
    "service-a" | SAMPLER_KEEP     | DEFAULT           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|1|0"

    "service-a" | SAMPLER_DROP     | AGENT_RATE        | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|0|1"
    "service-a" | SAMPLER_KEEP     | AGENT_RATE        | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|1|1"

    "service-a" | SAMPLER_DROP     | REMOTE_AUTO_RATE  | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|0|2"
    "service-a" | SAMPLER_KEEP     | REMOTE_AUTO_RATE  | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|1|2"

    "service-a" | USER_DROP        | RULE              | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|-1|3"
    "service-a" | USER_KEEP        | RULE              | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|2|3"

    "service-a" | USER_DROP        | MANUAL            | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|-1|4"
    "service-a" | USER_KEEP        | MANUAL            | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|2|4"

    "service-a" | USER_KEEP        | APPSEC            | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|2|5"

    "service-a" | USER_DROP        | REMOTE_USER_RATE  | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|-1|6"
    "service-a" | USER_KEEP        | REMOTE_USER_RATE  | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|2|6"
  }

  def "add upstream_services tag when upstream_services is empty"() {
    setup:
    def ddTags = DatadogTags.create("_dd.p.upstream_services=")

    when:
    ddTags.updateUpstreamServices(service, samplingPriority, samplingMechanism, rate)

    then:
    ddTags.encoded() == expected

    where:
    service     | samplingPriority | samplingMechanism | rate | expected
    "service-a" | UNSET            | UNKNOWN           | -1.0 | "_dd.p.upstream_services=" // keep ddTags as is if no changes applied
    "service-a" | SAMPLER_DROP     | UNKNOWN           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|0|-1"
  }

  def "add upstream_services tag when upstream_services is empty and there are other following tags"() {
    setup:
    def ddTags = DatadogTags.create("_dd.p.upstream_services=,_dd.p.hello=world")

    when:
    ddTags.updateUpstreamServices(service, samplingPriority, samplingMechanism, rate)

    then:
    ddTags.encoded() == expected

    where:
    service     | samplingPriority | samplingMechanism | rate | expected
    "service-a" | UNSET            | UNKNOWN           | -1.0 | "_dd.p.upstream_services=,_dd.p.hello=world" // keep ddTags as is if no changes applied
    "service-a" | SAMPLER_DROP     | UNKNOWN           | -1.0 | "_dd.p.upstream_services=c2VydmljZS1h|0|-1,_dd.p.hello=world"
  }

  def "avoid adding `;` separator if it's already there"() {
    setup:
    def ddTags = DatadogTags.create("_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;")

    when:
    ddTags.updateUpstreamServices(service, samplingPriority, samplingMechanism, rate)

    then:
    ddTags.encoded() == expected

    where:
    service     | samplingPriority | samplingMechanism | rate | expected
    "service-a" | UNSET            | UNKNOWN           | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;" // keep ddTags as is if no changes applied
    "service-a" | SAMPLER_DROP     | UNKNOWN           | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;c2VydmljZS1h|0|-1"
  }

  def "avoid adding `;` separator if it's already there and there are other following tags"() {
    setup:
    def ddTags = DatadogTags.create("_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;,_dd.p.hello=world")

    when:
    ddTags.updateUpstreamServices(service, samplingPriority, samplingMechanism, rate)

    then:
    ddTags.encoded() == expected

    where:
    service     | samplingPriority | samplingMechanism | rate | expected
    "service-a" | UNSET            | UNKNOWN           | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;,_dd.p.hello=world" // keep ddTags as is if no changes applied
    "service-a" | SAMPLER_DROP     | UNKNOWN           | -1.0 | "_dd.p.upstream_services=bWNudWx0eS13ZWI|0|1|0.1;c2VydmljZS1h|0|-1,_dd.p.hello=world"
  }
}
