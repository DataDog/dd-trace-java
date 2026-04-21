package foo.bar;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

public class TestFileInputStreamSuite {

  public static FileInputStream newFileInputStream(final String path) throws FileNotFoundException {
    return new FileInputStream(path);
  }

  public static FileInputStream newFileInputStream(final File file) throws FileNotFoundException {
    return new FileInputStream(file);
  }
}
