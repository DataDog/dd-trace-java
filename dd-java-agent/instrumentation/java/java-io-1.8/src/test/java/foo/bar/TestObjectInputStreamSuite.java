package foo.bar;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;

public class TestObjectInputStreamSuite {

  public static void init(final InputStream inputStream) {
    try {
      new ObjectInputStream(inputStream);
    } catch (IOException e) {
      // Irrelevant
    }
  }
}
