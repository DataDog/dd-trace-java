package datadog.smoketest

import datadog.environment.JavaVirtualMachine
import spock.lang.IgnoreIf

@IgnoreIf(reason = "JBoss Modules does not support Java 24+", value = {
  JavaVirtualMachine.isJavaVersionAtLeast(24)
})
class JBossModulesV2SmokeTest extends AbstractModulesSmokeTest {}
