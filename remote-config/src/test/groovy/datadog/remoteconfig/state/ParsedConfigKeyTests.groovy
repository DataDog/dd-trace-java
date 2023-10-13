package datadog.remoteconfig.state

import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import spock.lang.Specification

class ParsedConfigKeyTests extends Specification {
  void 'parse extract right segments'() {
    when:
    def parsed = ParsedConfigKey.parse(configKey)

    then:
    parsed.getOrg() == org
    parsed.getVersion() == version
    parsed.getProduct() == product
    parsed.getConfigId() == configId
    parsed.toString() == configKey

    where:
    configKey | org | version | product | configId

    "employee/ASM_DD/1.recommended.json/config" | "employee" | null| Product.ASM_DD | "1.recommended.json"

    "datadog/2/LIVE_DEBUGGING/Snapshot_1ba66cc9-146a-3479-9e66-2b63fd580f48/dog" | "datadog" | 2 | Product.LIVE_DEBUGGING | "Snapshot_1ba66cc9-146a-3479-9e66-2b63fd580f48"

    "datadog/2/NOT_REAL_PRODUCT/123/dogoz" | "datadog" | 2 | Product._UNKNOWN | "123"
  }

  void 'wrong format configKey fails'() {
    when:
    ParsedConfigKey.parse("foo")

    then:
    def e = thrown(ConfigurationPoller.ReportableException)
    e.message == "Not a valid config key: foo"
  }
}
