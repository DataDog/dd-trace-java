package foo.bar;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;

public class TestFileWriterCharsetSuite {

  public static FileWriter newFileWriter(final String path, final Charset charset)
      throws IOException {
    return new FileWriter(path, charset);
  }

  public static FileWriter newFileWriter(
      final String path, final Charset charset, final boolean append) throws IOException {
    return new FileWriter(path, charset, append);
  }

  public static FileWriter newFileWriter(final File file, final Charset charset)
      throws IOException {
    return new FileWriter(file, charset);
  }

  public static FileWriter newFileWriter(
      final File file, final Charset charset, final boolean append) throws IOException {
    return new FileWriter(file, charset, append);
  }
}
