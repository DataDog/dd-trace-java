package foo.bar;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;

public class TestFileOutputStreamSuite {

  public static FileOutputStream newFileOutputStream(final String path)
      throws FileNotFoundException {
    return new FileOutputStream(path);
  }
}
