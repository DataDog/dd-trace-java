package foo.bar;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestURLEncoderCallSiteSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestURLEncoderCallSiteSuite.class);

  public static String encode(final String value) {
    LOGGER.debug("Before encode {}", value);
    @SuppressWarnings("deprecation")
    final String result = URLEncoder.encode(value);
    LOGGER.debug("After encode {}", result);
    return result;
  }

  public static String encode(final String value, final String encoding)
      throws UnsupportedEncodingException {
    LOGGER.debug("Before encode {} {}", value, encoding);
    final String result = URLEncoder.encode(value, encoding);
    LOGGER.debug("After encode {}", result);
    return result;
  }
}
