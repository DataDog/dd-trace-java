package datadog.smoketest

import spock.lang.IgnoreIf

@IgnoreIf({
  // JBoss Modules 1.x doesn't support Java 17
  new BigDecimal(System.getProperty("java.specification.version")).isAtLeast(17.0)
})
class JBossModulesV1SmokeTest extends AbstractModulesSmokeTest {}
