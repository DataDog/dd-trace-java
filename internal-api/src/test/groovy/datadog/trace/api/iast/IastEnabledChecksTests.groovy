package datadog.trace.api.iast

import datadog.trace.api.Platform
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.IastConfig.IAST_GROOVY_INDY_ENABLED

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

  void 'test has indy support'() {
    when:
    injectSysConfig(IAST_GROOVY_INDY_ENABLED, "$enabled")
    final result = IastEnabledChecks.isGroovyIndyEnabled()

    then:
    result == enabled

    where:
    enabled | _
    true    | _
    false   | _
  }
}
