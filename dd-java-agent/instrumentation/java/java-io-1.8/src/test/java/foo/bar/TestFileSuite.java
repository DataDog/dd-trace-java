package foo.bar;

import java.io.File;
import java.net.URI;

public class TestFileSuite {

  public static File newFile(final String path) {
    return new File(path);
  }

  public static File newFile(final String parent, final String child) {
    return new File(parent, child);
  }

  public static File newFile(final File parent, final String child) {
    return new File(parent, child);
  }

  public static File newFile(final URI uri) {
    return new File(uri);
  }
}
