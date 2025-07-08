package datadog.trace.api.iast

import datadog.environment.JavaVirtualMachine
import datadog.trace.api.config.IastConfig
import datadog.trace.test.util.DDSpecification

class IastEnabledChecksTests extends DDSpecification {

  void 'test min java version'() {
    when:
    final enabled = IastEnabledChecks.isMajorJavaVersionAtLeast(majorJavaVersion)

    then:
    enabled == expected

    where:
    majorJavaVersion | expected
    '8'              | JavaVirtualMachine.isJavaVersionAtLeast(majorJavaVersion as int)
    '9'              | JavaVirtualMachine.isJavaVersionAtLeast(majorJavaVersion as int)
    '17'             | JavaVirtualMachine.isJavaVersionAtLeast(majorJavaVersion as int)
    'abcd'           | false
  }

  void 'test full detection'() {
    setup:
    injectSysConfig(IastConfig.IAST_DETECTION_MODE, mode.name())

    when:
    final isFull = IastEnabledChecks.isFullDetection()

    then:
    isFull == expected

    where:
    mode                      | expected
    IastDetectionMode.FULL    | true
    IastDetectionMode.DEFAULT | false
  }

  void 'test experimental propagation'() {
    setup:
    injectSysConfig(IastConfig.IAST_EXPERIMENTAL_PROPAGATION_ENABLED, value)

    when:
    final isExperimentalPropagationEnabled = IastEnabledChecks.isExperimentalPropagationEnabled()

    then:
    isExperimentalPropagationEnabled == expected

    where:
    value   | expected
    "true"  | true
    "false" | false
  }
}
