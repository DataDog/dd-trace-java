package datadog.trace.api.iast

import datadog.trace.api.Platform
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
    '8'              | Platform.isJavaVersionAtLeast(majorJavaVersion as int)
    '9'              | Platform.isJavaVersionAtLeast(majorJavaVersion as int)
    '17'             | Platform.isJavaVersionAtLeast(majorJavaVersion as int)
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
}
