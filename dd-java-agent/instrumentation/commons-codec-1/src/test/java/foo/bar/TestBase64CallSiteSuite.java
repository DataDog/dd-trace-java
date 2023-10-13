package foo.bar;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestBase64CallSiteSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestBase64CallSiteSuite.class);

  public static byte[] encode(final byte[] bytes) {
    LOGGER.debug("Before encode {}", bytes);
    final byte[] result = Base64.encodeBase64(bytes);
    LOGGER.debug("After encode {}", result);
    return result;
  }

  public static byte[] encode(final byte[] bytes, final Base64 encoder) throws EncoderException {
    LOGGER.debug("Before encode {} {}", bytes, encoder);
    final byte[] result = encoder.encode(bytes);
    LOGGER.debug("After encode {}", result);
    return result;
  }

  public static byte[] decode(final byte[] bytes) {
    LOGGER.debug("Before decode {}", bytes);
    final byte[] result = Base64.decodeBase64(bytes);
    LOGGER.debug("After decode {}", result);
    return result;
  }

  public static byte[] decode(final byte[] bytes, final Base64 encoder) throws DecoderException {
    LOGGER.debug("Before decode {} {}", bytes, encoder);
    final byte[] result = encoder.decode(bytes);
    LOGGER.debug("After decode {}", result);
    return result;
  }
}
