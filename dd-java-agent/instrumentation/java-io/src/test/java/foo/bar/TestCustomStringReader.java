package foo.bar;

import java.io.StringReader;

public class TestCustomStringReader extends StringReader {

  public TestCustomStringReader(String s) {
    super("Super" + s + (new StringReader("New" + s)));
  }
}
