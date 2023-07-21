package foo.bar;

import org.owasp.esapi.Encoder;

public class TestEncoderSuite {

  private Encoder encoder;

  TestEncoderSuite(Encoder encoder) {
    this.encoder = encoder;
  }

  public String encodeForHTML(String input) {
    return encoder.encodeForHTML(input);
  }
}
