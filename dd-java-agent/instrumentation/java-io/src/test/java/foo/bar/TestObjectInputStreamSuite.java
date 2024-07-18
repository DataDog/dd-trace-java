package foo.bar;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;

public class TestObjectInputStreamSuite {

  public static ObjectInputStream init(final InputStream inputStream) throws IOException {
    return new ObjectInputStream(inputStream);
  }
}
