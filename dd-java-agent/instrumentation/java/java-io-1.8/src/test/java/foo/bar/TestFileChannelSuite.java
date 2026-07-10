package foo.bar;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;

public class TestFileChannelSuite {

  public static FileChannel openRead(final Path path) throws IOException {
    return FileChannel.open(path, StandardOpenOption.READ);
  }

  public static FileChannel openWrite(final Path path) throws IOException {
    return FileChannel.open(
        path,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  public static FileChannel openWithOptions(final Path path, final OpenOption... options)
      throws IOException {
    return FileChannel.open(path, options);
  }

  public static FileChannel openWithSet(
      final Path path, final Set<? extends OpenOption> options, final FileAttribute<?>... attrs)
      throws IOException {
    return FileChannel.open(path, options, attrs);
  }
}
