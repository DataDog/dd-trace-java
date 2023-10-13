package foo.bar;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestPathsSuite {

  public static Path get(final String first, final String... more) {
    return Paths.get(first, more);
  }

  public static Path get(final URI uri) {
    return Paths.get(uri);
  }
}
