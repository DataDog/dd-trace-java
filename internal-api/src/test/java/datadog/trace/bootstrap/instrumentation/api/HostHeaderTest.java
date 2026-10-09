package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class HostHeaderTest {

  @TableTest({
    "scenario         | header                  | host        | port",
    "host only        | example.com             | example.com | -1  ",
    "host and port    | example.com:8080        | example.com | 8080",
    "IPv4 and port    | 10.0.0.1:80             | 10.0.0.1    | 80  ",
    "IPv6 only        | '[::1]'                 | '[::1]'     | -1  ",
    "IPv6 and port    | '[::1]:8443'            | '[::1]'     | 8443",
    "empty port       | example.com:            | example.com | -1  ",
    "non-numeric port | example.com:http        | example.com | -1  ",
    "port overflow    | example.com:99999999999 | example.com | -1  ",
    "negative port    | example.com:-1          | example.com | -1  ",
    "empty            | ''                      | ''          | -1  "
  })
  void splitsHostHeader(String header, String host, int port) {
    assertEquals(host, HostHeader.host(header));
    assertEquals(port, HostHeader.port(header));
  }

  @Test
  void nullHeader() {
    assertNull(HostHeader.host(null));
    assertEquals(-1, HostHeader.port(null));
    assertEquals(-1, HostHeader.portSeparator(null));
  }
}
