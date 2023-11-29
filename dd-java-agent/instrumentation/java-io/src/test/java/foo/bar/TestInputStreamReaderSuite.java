package foo.bar;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

public class TestInputStreamReaderSuite {

  public static InputStreamReader init(final InputStream in, Charset charset) {
    return new InputStreamReader(in, charset);
  }
}
