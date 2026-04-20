package foo.bar;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class TestFileWriterSuite {

  public static FileWriter newFileWriter(final String path) throws IOException {
    return new FileWriter(path);
  }

  public static FileWriter newFileWriter(final String path, final boolean append)
      throws IOException {
    return new FileWriter(path, append);
  }

  public static FileWriter newFileWriter(final File file) throws IOException {
    return new FileWriter(file);
  }

  public static FileWriter newFileWriter(final File file, final boolean append) throws IOException {
    return new FileWriter(file, append);
  }
}
