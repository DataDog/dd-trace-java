package foo.bar;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestURLDecoderCallSiteSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestURLDecoderCallSiteSuite.class);

  public static String decode(final String value) {
    LOGGER.debug("Before encode {}", value);
    @SuppressWarnings("deprecation")
    final String result = URLDecoder.decode(value);
    LOGGER.debug("After encode {}", result);
    return result;
  }

  public static String decode(final String value, final String encoding)
      throws UnsupportedEncodingException {
    LOGGER.debug("Before encode {} {}", value, encoding);
    final String result = URLDecoder.decode(value, encoding);
    LOGGER.debug("After encode {}", result);
    return result;
  }
}
