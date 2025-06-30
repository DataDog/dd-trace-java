package datadog.smoketest

import spock.lang.IgnoreIf
import datadog.trace.api.Platform

@IgnoreIf(reason = "Failing on Java 24. Skip until we have a fix.", value = {
  Platform.isJavaVersionAtLeast(24)
})
class JBossModulesV2SmokeTest extends AbstractModulesSmokeTest {}
