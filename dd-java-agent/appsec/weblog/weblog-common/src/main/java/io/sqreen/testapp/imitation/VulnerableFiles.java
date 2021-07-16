package io.sqreen.testapp.imitation;

import com.google.common.io.Closer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A files-related vulnerability imitations. */
public final class VulnerableFiles {

  private static final File filesLocation = new File("/tmp");

  /**
   * Stores the input in a temporary file, returning the hash of its contents. The name of the file
   * is randomly generated and cannot be obtained by the caller.
   *
   * @param is the input stream connected to the uploaded file
   * @return md5 hash of the file
   * @throws IOException
   * @throws NoSuchAlgorithmException
   */
  public static String store(InputStream is) throws IOException, NoSuchAlgorithmException {

    Closer closer = Closer.create();
    closer.register(is);

    MessageDigest digest = MessageDigest.getInstance("MD5");
    File storedFile = File.createTempFile("sample-app", ".tmp", filesLocation);
    FileOutputStream os = closer.register(new FileOutputStream(storedFile));

    try {

      byte[] byteBuffer = new byte[8 * 1024];
      int byteRead;

      while ((byteRead = is.read(byteBuffer)) != -1) {
        // update the digest
        digest.update(byteBuffer, 0, byteRead);
        // append buffer
        os.write(byteBuffer, 0, byteRead);
      }

      return String.format("%032x", new BigInteger(1, digest.digest()));

    } finally {
      closer.close();
    }
  }

  /**
   * Returns a {@link File} associated with specified file path.
   *
   * @param filename a file to read
   * @return an {@link File} referring to the specified string
   * @throws IOException
   */
  public static File getFile(String filename) {
    return new File(filename);
  }

  private VulnerableFiles() {
    /**/
  }
}
