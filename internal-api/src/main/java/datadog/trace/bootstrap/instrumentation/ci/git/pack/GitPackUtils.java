package datadog.trace.bootstrap.instrumentation.ci.git.pack;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

public class GitPackUtils {

  // Version in a .idx file v1.
  private static final short V1_VERSION = 1;
  // First 4 bytes in a .idx file starting from v2.
  protected static final byte[] HEADER = new byte[] {-1, 116, 79, 99};

  /**
   * Extracts the packfile version reading the IDX file. Starting from v2, there are dedicated
   * 4-bytes to stored the version.
   *
   * <p>If we don't find the starting header, the version is always V1
   *
   * @param idxFile
   * @return
   * @throws IOException
   */
  public static short extractGitPackVersion(final File idxFile) throws IOException {
    final RandomAccessFile raf = new RandomAccessFile(idxFile, "r");
    final byte[] header = readBytes(raf, 4);
    if (!Arrays.equals(header, HEADER)) {
      return V1_VERSION;
    }

    final int version = raf.readInt();
    return (short) version;
  }

  public static byte[] readBytes(final RandomAccessFile file, final int numBytes)
      throws IOException {
    final byte[] buff = new byte[numBytes];
    file.readFully(buff);
    return buff;
  }

  public static byte[] hexToByteArray(final String s) {
    final int len = s.length();
    final byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] =
          (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
    }
    return data;
  }

  /**
   * Get the pack file based on the idx file. The idx/pack files have the same name but different
   * extension (.idx or .pack)
   *
   * @param idxFile
   * @return pack file based on the idx file
   */
  public static File getPackFile(final File idxFile) {
    final int i = idxFile.getName().lastIndexOf('.');
    final String name = idxFile.getName().substring(0, i);
    return new File(idxFile.getParent(), name + ".pack");
  }

  public static void seek(final RandomAccessFile file, final long pos, final SeekOrigin origin)
      throws IOException {
    switch (origin) {
      case BEGIN:
        file.seek(pos);
        break;
      case CURRENT:
        final long current = file.getFilePointer();
        file.seek(pos + current);
        break;
    }
  }

  public enum SeekOrigin {
    BEGIN,
    CURRENT
  }
}
