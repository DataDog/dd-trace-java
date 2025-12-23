package foo.bar;

import org.owasp.esapi.Encoder;
import org.owasp.esapi.codecs.Codec;

public class TestEncoderSuite {

  private Encoder encoder;

  TestEncoderSuite(Encoder encoder) {
    this.encoder = encoder;
  }

  public String encodeForHTML(String input) {
    return encoder.encodeForHTML(input);
  }

  public String canonicalize(String input) {
    return encoder.canonicalize(input);
  }

  public String canonicalize(String input, boolean strict) {
    return encoder.canonicalize(input, strict);
  }

  public String canonicalize(String input, boolean strict, boolean restrictMixed) {
    return encoder.canonicalize(input, strict, restrictMixed);
  }

  public String encodeForLDAP(String input) {
    return encoder.encodeForLDAP(input);
  }

  public String encodeForOS(Codec codec, String input) {
    return encoder.encodeForOS(codec, input);
  }

  public String encodeForSQL(Codec codec, String input) {
    return encoder.encodeForSQL(codec, input);
  }
}
