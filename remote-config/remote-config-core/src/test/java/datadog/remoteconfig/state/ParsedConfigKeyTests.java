package datadog.remoteconfig.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.remoteconfig.Product;
import datadog.remoteconfig.ReportableException;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ParsedConfigKeyTests {

  @TableTest({
    "scenario        | configKey                                                                  | org      | version | product        | configId                                     ",
    "no version      | employee/ASM_DD/1.recommended.json/config                                  | employee |         | ASM_DD         | 1.recommended.json                           ",
    "live debugging  | datadog/2/LIVE_DEBUGGING/Snapshot_1ba66cc9-146a-3479-9e66-2b63fd580f48/dog | datadog  | 2       | LIVE_DEBUGGING | Snapshot_1ba66cc9-146a-3479-9e66-2b63fd580f48",
    "unknown product | datadog/2/NOT_REAL_PRODUCT/123/dogoz                                       | datadog  | 2       | _UNKNOWN       | 123                                          "
  })
  void parseExtractsRightSegments(
      String configKey, String org, Integer version, Product product, String configId) {
    ParsedConfigKey parsed = ParsedConfigKey.parse(configKey);

    assertEquals(org, parsed.getOrg());
    assertEquals(version, parsed.getVersion());
    assertEquals(product, parsed.getProduct());
    assertEquals(configId, parsed.getConfigId());
    assertEquals(configKey, parsed.toString());
  }

  @Test
  void wrongFormatConfigKeyFails() {
    ReportableException exception =
        assertThrows(ReportableException.class, () -> ParsedConfigKey.parse("foo"));
    assertEquals("Not a valid config key: foo", exception.getMessage());
  }
}
