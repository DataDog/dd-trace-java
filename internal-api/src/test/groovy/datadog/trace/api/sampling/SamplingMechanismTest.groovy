package datadog.trace.api.sampling

import spock.lang.Specification
import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*

class SamplingMechanismTest extends Specification {

  def "test validation"() {
    expect:
    validateWithSamplingPriority(mechanism, priority) == valid

    where:
    mechanism        | priority     | valid
    UNKNOWN          | UNSET        | true
    UNKNOWN          | SAMPLER_DROP | true
    UNKNOWN          | SAMPLER_KEEP | true
    UNKNOWN          | USER_DROP    | true
    UNKNOWN          | USER_KEEP    | true

    DEFAULT          | UNSET        | false
    DEFAULT          | SAMPLER_DROP | true
    DEFAULT          | SAMPLER_KEEP | true
    DEFAULT          | USER_DROP    | false
    DEFAULT          | USER_KEEP    | false

    AGENT_RATE       | UNSET        | false
    AGENT_RATE       | SAMPLER_DROP | true
    AGENT_RATE       | SAMPLER_KEEP | true
    AGENT_RATE       | USER_DROP    | false
    AGENT_RATE       | USER_KEEP    | false

    REMOTE_AUTO_RATE | UNSET        | false
    REMOTE_AUTO_RATE | SAMPLER_DROP | true
    REMOTE_AUTO_RATE | SAMPLER_KEEP | true
    REMOTE_AUTO_RATE | USER_DROP    | false
    REMOTE_AUTO_RATE | USER_KEEP    | false

    RULE             | UNSET        | false
    RULE             | SAMPLER_DROP | false
    RULE             | SAMPLER_KEEP | false
    RULE             | USER_DROP    | true
    RULE             | USER_KEEP    | true

    MANUAL           | UNSET        | false
    MANUAL           | SAMPLER_DROP | false
    MANUAL           | SAMPLER_KEEP | false
    MANUAL           | USER_DROP    | true
    MANUAL           | USER_KEEP    | true

    REMOTE_USER_RATE | UNSET        | false
    REMOTE_USER_RATE | SAMPLER_DROP | false
    REMOTE_USER_RATE | SAMPLER_KEEP | false
    REMOTE_USER_RATE | USER_DROP    | true
    REMOTE_USER_RATE | USER_KEEP    | true

    APPSEC           | UNSET        | false
    APPSEC           | SAMPLER_DROP | false
    APPSEC           | SAMPLER_KEEP | false
    APPSEC           | USER_DROP    | false
    APPSEC           | USER_KEEP    | true
  }
}
