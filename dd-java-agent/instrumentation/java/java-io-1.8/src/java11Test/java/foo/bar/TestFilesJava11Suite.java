package foo.bar;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

public class TestFilesJava11Suite {

  public static Path writeString(
      final Path path, final CharSequence content, final OpenOption... options) throws IOException {
    return Files.writeString(path, content, options);
  }

  public static Path writeString(
      final Path path,
      final CharSequence content,
      final Charset charset,
      final OpenOption... options)
      throws IOException {
    return Files.writeString(path, content, charset, options);
  }

  public static String readString(final Path path) throws IOException {
    return Files.readString(path);
  }

  public static String readString(final Path path, final Charset charset) throws IOException {
    return Files.readString(path, charset);
  }
}
