package datadog.trace.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import org.tabletest.junit.TableTest;

class PercentEscaperTest {

  private final PercentEscaper escaper = PercentEscaper.create();

  @TableTest({
    "scenario                        | value        | expected                       ",
    "safe ascii is untouched         | 'plainvalue' | 'plainvalue'                   ",
    "unsafe ascii is escaped         | 'a,b'        | 'a%2Cb'                        ",
    "non-ascii at the start          | '你好'       | '%E4%BD%A0%E5%A5%BD'           ",
    "non-ascii after an escaped char | 'a,b中'      | 'a%2Cb%E4%B8%AD'               ",
    "non-ascii interleaved with safe | '中a中a中'   | '%E4%B8%ADa%E4%B8%ADa%E4%B8%AD'",
    "two byte non-ascii              | 'café crème' | 'caf%C3%A9%20cr%C3%A8me'       ",
    "four byte supplementary         | 'a,b😀'      | 'a%2Cb%F0%9F%98%80'            "
  })
  void escapeValueEncodesEveryNonAsciiCharacter(String value, String expected) {
    assertEquals(expected, this.escaper.escapeValue(value).data);
  }

  /** Keys use a separate, longer octet table than values, so cover that path too. */
  @TableTest({
    "scenario                        | key     | expected            ",
    "non-ascii at the start          | '你好'  | '%E4%BD%A0%E5%A5%BD'",
    "non-ascii after an escaped char | 'a/b中' | 'a%2Fb%E4%B8%AD'    "
  })
  void escapeKeyEncodesEveryNonAsciiCharacter(String key, String expected) {
    assertEquals(expected, this.escaper.escapeKey(key).data);
  }

  /**
   * Independently verifies the escaped bytes are correct, rather than relying on the hand-written
   * expectations above, and that nothing non-ASCII reaches the header.
   */
  @TableTest({
    "scenario                | value       ",
    "three byte non-ascii    | '你好世界'  ",
    "two byte non-ascii      | 'café crème'",
    "four byte supplementary | '😀🎉'      "
  })
  void escapedValueIsAsciiOnlyAndDecodesBackToTheInput(String value)
      throws UnsupportedEncodingException {
    String escaped = this.escaper.escapeValue(value).data;
    for (int i = 0; i < escaped.length(); i++) {
      char c = escaped.charAt(i);
      assertTrue(c <= '~', "expected pure ASCII output but got '" + c + "' in " + escaped);
    }
    assertEquals(value, URLDecoder.decode(escaped, "UTF-8"));
  }
}
