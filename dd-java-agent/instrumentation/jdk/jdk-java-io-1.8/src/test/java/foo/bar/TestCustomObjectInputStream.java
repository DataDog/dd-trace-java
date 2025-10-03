package foo.bar;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;

public class TestCustomObjectInputStream extends ObjectInputStream {

  public TestCustomObjectInputStream(final InputStream in) throws IOException {
    super(in);
  }
}
