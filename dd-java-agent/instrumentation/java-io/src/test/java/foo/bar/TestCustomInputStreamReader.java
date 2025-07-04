package foo.bar;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

public class TestCustomInputStreamReader extends InputStreamReader {

  public TestCustomInputStreamReader(final InputStream in) throws IOException {
    super(in);
  }

  public TestCustomInputStreamReader(final InputStream in, final Charset charset)
      throws IOException {
    // XXX: DO NOT MODIFY THIS CODE. This is testing a very specific error (APPSEC-58131).
    // This caused the following error:
    //   VerifyError: Inconsistent stackmap frames at branch target \d
    //   Reason: urrent frame's stack size doesn't match stackmap.
    // To trigger this, it is necessary to consume an argument after the super call.
    super(in, charset);
    if (charset != null) {
      System.out.println("Using charset: " + charset.name());
    }
  }
}
