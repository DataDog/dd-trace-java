package datadog.trace.api.iast

import datadog.trace.api.Platform
import spock.lang.Specification

class IastEnabledChecksTests extends Specification {

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
}
