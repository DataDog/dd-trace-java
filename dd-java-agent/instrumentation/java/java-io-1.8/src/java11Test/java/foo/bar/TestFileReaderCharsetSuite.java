package foo.bar;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;

public class TestFileReaderCharsetSuite {

  public static FileReader newFileReader(final String path, final Charset charset)
      throws IOException {
    return new FileReader(path, charset);
  }

  public static FileReader newFileReader(final File file, final Charset charset)
      throws IOException {
    return new FileReader(file, charset);
  }
}
