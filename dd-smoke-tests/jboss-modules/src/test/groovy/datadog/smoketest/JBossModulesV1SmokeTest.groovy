package datadog.smoketest

import datadog.environment.JavaVirtualMachine
import spock.lang.IgnoreIf

@IgnoreIf(reason = "JBoss Modules 1.x doesn't support Java 17", value = {
  JavaVirtualMachine.isJavaVersionAtLeast(17)
})
class JBossModulesV1SmokeTest extends AbstractModulesSmokeTest {}
