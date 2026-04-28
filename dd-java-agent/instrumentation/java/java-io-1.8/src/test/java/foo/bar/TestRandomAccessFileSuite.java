package foo.bar;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class TestRandomAccessFileSuite {

  public static RandomAccessFile newRandomAccessFile(final String name, final String mode)
      throws IOException {
    return new RandomAccessFile(name, mode);
  }

  public static RandomAccessFile newRandomAccessFile(final File file, final String mode)
      throws IOException {
    return new RandomAccessFile(file, mode);
  }
}
