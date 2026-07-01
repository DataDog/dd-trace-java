package foo.bar;

import java.net.URI;
import java.nio.file.Path;

public class TestPathOfSuite {

  public static Path of(final String first, final String... more) {
    return Path.of(first, more);
  }

  public static Path of(final URI uri) {
    return Path.of(uri);
  }
}
