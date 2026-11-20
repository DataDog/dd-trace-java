package datadog.trace.bootstrap.instrumentation.api.http.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class IPAddressUtilTest {
  @TableTest({
    "Input            | Expected       ",
    "0.0.0.0          | 0.0.0.0        ",
    "255.255.255.255  | 255.255.255.255",
    "127.1            | 127.0.0.1      ",
    "127.0.1          | 127.0.0.1      ",
    "2130706433       | 127.0.0.1      ",
    "001.002.003.004  | 1.2.3.4        ",
    "''               |                ",
    "1234567890123456 |                ",
    ".1.2.3           |                ",
    "1..2.3           |                ",
    "1.2.3.           |                ",
    "1.2.3.4.5        |                ",
    "256.1.2.3        |                ",
    "1.2.65536        |                ",
    "4294967296       |                ",
    "-1.2.3.4         |                ",
    "a.2.3.4          |                ",
    "' 1.2.3.4'       |                "
  })
  void parsesIPv4Literals(String input, String expected) throws UnknownHostException {
    assertArrayEquals(bytes(expected), IPAddressUtil.textToNumericFormatV4(input));
    assertEquals(expected != null, IPAddressUtil.isIPv4LiteralAddress(input));
  }

  @TableTest({
    "Input                   | Expected            ",
    "::                      | ::                  ",
    "::1                     | ::1                 ",
    "2001:db8::1             | 2001:db8::1         ",
    "2001:db8:0:0:1:2:3:4    | 2001:db8:0:0:1:2:3:4",
    "1:2:3:4:5:6:7:8         | 1:2:3:4:5:6:7:8     ",
    "1:2:3:4:5:6:7::         | 1:2:3:4:5:6:7::     ",
    "::ffff:192.0.2.1        | 192.0.2.1           ",
    "2001:db8::192.0.2.1     | 2001:db8::192.0.2.1 ",
    "fe80::1%eth0            | fe80::1             ",
    "''                      |                     ",
    "1                       |                     ",
    ":                       |                     ",
    "1:                      |                     ",
    ":::                     |                     ",
    "1::2::3                 |                     ",
    "12345::                 |                     ",
    "1:2:3:4:5:6:7:8:9       |                     ",
    "1:2:3:4:5:6:7           |                     ",
    "1:2:3:4:5:6:7:8::       |                     ",
    "1:2:3:4:5:6:7:8:9:      |                     ",
    "1:2:3:4:5:6:7:192.0.2.1 |                     ",
    "::ffff:192.0.1          |                     ",
    "::ffff:192.0.2.256      |                     ",
    "2001:db8::g             |                     ",
    "fe80::1%                |                     "
  })
  void parsesIPv6Literals(String input, String expected) throws UnknownHostException {
    assertArrayEquals(bytes(expected), IPAddressUtil.textToNumericFormatV6(input));
    assertEquals(expected != null, IPAddressUtil.isIPv6LiteralAddress(input));
  }

  @Test
  void convertsOnlyTheCompleteIPv4MappedPrefix() {
    byte[] mapped = new byte[16];
    mapped[10] = (byte) 0xff;
    mapped[11] = (byte) 0xff;
    mapped[12] = 1;
    mapped[13] = 2;
    mapped[14] = 3;
    mapped[15] = 4;
    assertArrayEquals(new byte[] {1, 2, 3, 4}, IPAddressUtil.convertFromIPv4MappedAddress(mapped));
    assertNull(IPAddressUtil.convertFromIPv4MappedAddress(new byte[4]));
    for (int i = 0; i < 12; i++) {
      byte[] invalid = mapped.clone();
      invalid[i] ^= 1;
      assertNull(IPAddressUtil.convertFromIPv4MappedAddress(invalid), "prefix byte " + i);
    }
  }

  private static byte[] bytes(String address) throws UnknownHostException {
    return address == null ? null : InetAddress.getByName(address).getAddress();
  }
}
