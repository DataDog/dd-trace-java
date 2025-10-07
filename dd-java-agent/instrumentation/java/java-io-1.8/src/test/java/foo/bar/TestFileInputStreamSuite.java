package foo.bar;

import java.io.FileInputStream;
import java.io.FileNotFoundException;

public class TestFileInputStreamSuite {

  public static FileInputStream newFileInputStream(final String path) throws FileNotFoundException {
    return new FileInputStream(path);
  }
}
