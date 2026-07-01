package foo.bar;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class TestFileReaderSuite {

  public static FileReader newFileReader(final String path) throws IOException {
    return new FileReader(path);
  }

  public static FileReader newFileReader(final File file) throws IOException {
    return new FileReader(file);
  }
}
