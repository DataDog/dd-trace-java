package com.datadog.openfeature.internal.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.tabletest.junit.TableTest;

class SiteHostTest {
  @TableTest({
    "scenario  | site                | host                      ",
    "default   | 'datadoghq.com'     | 'intake.datadoghq.com'    ",
    "eu        | 'datadoghq.eu'      | 'intake.datadoghq.eu'     ",
    "subdomain | 'us3.datadoghq.com' | 'intake.us3.datadoghq.com'"
  })
  void buildsHostFromSite(final String site, final String host) {
    assertEquals(host, SiteHost.of("intake", site));
  }

  @TableTest({
    "scenario  | site                         ",
    "user info | 'datadoghq.com@other.example'",
    "path      | 'datadoghq.com/other'        ",
    "fragment  | 'datadoghq.com#'             ",
    "port      | 'datadoghq.com:8443'         ",
    "query     | 'datadoghq.com?other'        ",
    "space     | 'datadoghq com'              "
  })
  void rejectsSiteThatIsNotAHostName(final String site) {
    assertThrows(IllegalArgumentException.class, () -> SiteHost.of("intake", site));
  }
}
