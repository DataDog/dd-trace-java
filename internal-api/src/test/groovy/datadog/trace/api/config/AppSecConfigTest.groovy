package datadog.trace.api.config

import datadog.trace.api.Config
import datadog.trace.api.ProductActivationConfig
import datadog.trace.test.util.DDSpecification
import spock.lang.Unroll

class AppSecConfigTest extends DDSpecification {

  @Unroll
  def "appsec state with sys = #sys env = #env"() {
    setup:
    if (sys != null) {
      System.setProperty("dd.appsec.enabled", sys)
    }
    if (env != null) {
      environmentVariables.set("DD_APPSEC_ENABLED", env)
    }

    when:
    def config = new Config()

    then:
    config.getAppSecEnabledConfig() == res

    where:
    sys        | env        | res
    null       | null       | ProductActivationConfig.ENABLED_INACTIVE
    null       | ""         | ProductActivationConfig.ENABLED_INACTIVE
    null       | "inactive" | ProductActivationConfig.ENABLED_INACTIVE
    null       | "false"    | ProductActivationConfig.FULLY_DISABLED
    null       | "0"        | ProductActivationConfig.FULLY_DISABLED
    null       | "invalid"  | ProductActivationConfig.FULLY_DISABLED
    null       | "true"     | ProductActivationConfig.FULLY_ENABLED
    null       | "1"        | ProductActivationConfig.FULLY_ENABLED
    ""         | null       | ProductActivationConfig.ENABLED_INACTIVE
    ""         | ""         | ProductActivationConfig.ENABLED_INACTIVE
    ""         | "inactive" | ProductActivationConfig.ENABLED_INACTIVE
    ""         | "false"    | ProductActivationConfig.FULLY_DISABLED
    ""         | "0"        | ProductActivationConfig.FULLY_DISABLED
    ""         | "invalid"  | ProductActivationConfig.FULLY_DISABLED
    ""         | "true"     | ProductActivationConfig.FULLY_ENABLED
    ""         | "1"        | ProductActivationConfig.FULLY_ENABLED
    "inactive" | null       | ProductActivationConfig.ENABLED_INACTIVE
    "inactive" | ""         | ProductActivationConfig.ENABLED_INACTIVE
    "inactive" | "inactive" | ProductActivationConfig.ENABLED_INACTIVE
    "inactive" | "false"    | ProductActivationConfig.ENABLED_INACTIVE
    "inactive" | "0"        | ProductActivationConfig.ENABLED_INACTIVE
    "inactive" | "invalid"  | ProductActivationConfig.ENABLED_INACTIVE
    "inactive" | "true"     | ProductActivationConfig.ENABLED_INACTIVE
    "inactive" | "1"        | ProductActivationConfig.ENABLED_INACTIVE
    "false"    | null       | ProductActivationConfig.FULLY_DISABLED
    "false"    | ""         | ProductActivationConfig.FULLY_DISABLED
    "false"    | "inactive" | ProductActivationConfig.FULLY_DISABLED
    "false"    | "false"    | ProductActivationConfig.FULLY_DISABLED
    "false"    | "0"        | ProductActivationConfig.FULLY_DISABLED
    "false"    | "invalid"  | ProductActivationConfig.FULLY_DISABLED
    "false"    | "true"     | ProductActivationConfig.FULLY_DISABLED
    "false"    | "1"        | ProductActivationConfig.FULLY_DISABLED
    "0"        | null       | ProductActivationConfig.FULLY_DISABLED
    "true"     | null       | ProductActivationConfig.FULLY_ENABLED
    "true"     | ""         | ProductActivationConfig.FULLY_ENABLED
    "true"     | "inactive" | ProductActivationConfig.FULLY_ENABLED
    "true"     | "false"    | ProductActivationConfig.FULLY_ENABLED
    "true"     | "0"        | ProductActivationConfig.FULLY_ENABLED
    "true"     | "invalid"  | ProductActivationConfig.FULLY_ENABLED
    "true"     | "true"     | ProductActivationConfig.FULLY_ENABLED
    "true"     | "1"        | ProductActivationConfig.FULLY_ENABLED
    "1"        | null       | ProductActivationConfig.FULLY_ENABLED
  }
}
