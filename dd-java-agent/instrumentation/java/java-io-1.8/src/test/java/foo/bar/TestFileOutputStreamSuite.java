package foo.bar;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

public class TestFileOutputStreamSuite {

  public static FileOutputStream newFileOutputStream(final String path)
      throws FileNotFoundException {
    return new FileOutputStream(path);
  }

  public static FileOutputStream newFileOutputStream(final String path, final boolean append)
      throws FileNotFoundException {
    return new FileOutputStream(path, append);
  }

  public static FileOutputStream newFileOutputStream(final File file) throws FileNotFoundException {
    return new FileOutputStream(file);
  }

  public static FileOutputStream newFileOutputStream(final File file, final boolean append)
      throws FileNotFoundException {
    return new FileOutputStream(file, append);
  }
}
