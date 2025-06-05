package datadog.trace.api.sampling

import datadog.trace.test.util.DDSpecification
import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*

class SamplingMechanismTest extends DDSpecification {

  static userDropX = USER_DROP - 1
  static userKeepX = USER_KEEP + 1

  def "test validation"() {
    expect:
    validateWithSamplingPriority(mechanism, priority) == valid

    where:
    mechanism         | priority     | valid
    UNKNOWN           | UNSET        | true
    UNKNOWN           | SAMPLER_DROP | true
    UNKNOWN           | SAMPLER_KEEP | true
    UNKNOWN           | USER_DROP    | true
    UNKNOWN           | USER_KEEP    | true
    UNKNOWN           | userDropX    | true
    UNKNOWN           | userKeepX    | true

    DEFAULT           | UNSET        | false
    DEFAULT           | SAMPLER_DROP | true
    DEFAULT           | SAMPLER_KEEP | true
    DEFAULT           | USER_DROP    | false
    DEFAULT           | USER_KEEP    | false
    DEFAULT           | userDropX    | false
    DEFAULT           | userKeepX    | false

    AGENT_RATE        | UNSET        | false
    AGENT_RATE        | SAMPLER_DROP | true
    AGENT_RATE        | SAMPLER_KEEP | true
    AGENT_RATE        | USER_DROP    | false
    AGENT_RATE        | USER_KEEP    | false
    AGENT_RATE        | userDropX    | false
    AGENT_RATE        | userKeepX    | false

    REMOTE_AUTO_RATE  | UNSET        | false
    REMOTE_AUTO_RATE  | SAMPLER_DROP | true
    REMOTE_AUTO_RATE  | SAMPLER_KEEP | true
    REMOTE_AUTO_RATE  | USER_DROP    | false
    REMOTE_AUTO_RATE  | USER_KEEP    | false
    REMOTE_AUTO_RATE  | userDropX    | false
    REMOTE_AUTO_RATE  | userKeepX    | false

    LOCAL_USER_RULE   | UNSET        | false
    LOCAL_USER_RULE   | SAMPLER_DROP | false
    LOCAL_USER_RULE   | SAMPLER_KEEP | false
    LOCAL_USER_RULE   | USER_DROP    | true
    LOCAL_USER_RULE   | USER_KEEP    | true
    LOCAL_USER_RULE   | userDropX    | false
    LOCAL_USER_RULE   | userKeepX    | false

    MANUAL            | UNSET        | false
    MANUAL            | SAMPLER_DROP | false
    MANUAL            | SAMPLER_KEEP | false
    MANUAL            | USER_DROP    | true
    MANUAL            | USER_KEEP    | true
    MANUAL            | userDropX    | false
    MANUAL            | userKeepX    | false

    REMOTE_USER_RATE  | UNSET        | false
    REMOTE_USER_RATE  | SAMPLER_DROP | false
    REMOTE_USER_RATE  | SAMPLER_KEEP | false
    REMOTE_USER_RATE  | USER_DROP    | true
    REMOTE_USER_RATE  | USER_KEEP    | true
    REMOTE_USER_RATE  | userDropX    | false
    REMOTE_USER_RATE  | userKeepX    | false

    APPSEC            | UNSET        | false
    APPSEC            | SAMPLER_DROP | true
    APPSEC            | SAMPLER_KEEP | true
    APPSEC            | USER_DROP    | false
    APPSEC            | USER_KEEP    | true
    APPSEC            | userDropX    | false
    APPSEC            | userKeepX    | false

    DATA_JOBS         | UNSET        | false
    DATA_JOBS         | SAMPLER_DROP | false
    DATA_JOBS         | SAMPLER_KEEP | false
    DATA_JOBS         | USER_DROP    | false
    DATA_JOBS         | USER_KEEP    | true
    DATA_JOBS         | userDropX    | false
    DATA_JOBS         | userKeepX    | false

    EXTERNAL_OVERRIDE | UNSET        | false
    EXTERNAL_OVERRIDE | SAMPLER_DROP | false
    EXTERNAL_OVERRIDE | SAMPLER_KEEP | false
    EXTERNAL_OVERRIDE | USER_DROP    | false
    EXTERNAL_OVERRIDE | USER_KEEP    | false
    EXTERNAL_OVERRIDE | userDropX    | false
    EXTERNAL_OVERRIDE | userKeepX    | false
  }

  void 'Test canAvoidSamplingPriorityLock'(){
    setup:
    injectSysConfig("dd.apm.tracing.enabled", "false")

    expect:
    canAvoidSamplingPriorityLock(priority, mechanism) == valid

    where:
    mechanism         | priority     | valid
    APPSEC            | UNSET        | true
    APPSEC            | SAMPLER_KEEP | true
    UNKNOWN           | SAMPLER_KEEP | false
    DEFAULT           | SAMPLER_KEEP | false
    AGENT_RATE        | SAMPLER_KEEP | false
    REMOTE_AUTO_RATE  | SAMPLER_KEEP | false
    LOCAL_USER_RULE   | SAMPLER_KEEP | false
    MANUAL            | SAMPLER_KEEP | false
    REMOTE_USER_RATE  | SAMPLER_KEEP | false
    DATA_JOBS         | SAMPLER_KEEP | false
    EXTERNAL_OVERRIDE | SAMPLER_KEEP | false
  }
}
