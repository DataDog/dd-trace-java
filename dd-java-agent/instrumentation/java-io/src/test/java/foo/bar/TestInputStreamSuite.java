package foo.bar;

import java.io.InputStream;
import java.io.PushbackInputStream;

public class TestInputStreamSuite {

  public static InputStream pushbackInputStreamFromIS(final InputStream is) {
    return new PushbackInputStream(is);
  }
}
