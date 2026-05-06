package foo.bar;

import java.nio.file.Path;

public class TestPathSuite {

  public static Path resolve(final Path parent, final String other) {
    return parent.resolve(other);
  }

  public static Path resolveSibling(final Path parent, final String other) {
    return parent.resolveSibling(other);
  }

  public static Path resolveWithPath(final Path parent, final Path other) {
    return parent.resolve(other);
  }

  public static Path resolveSiblingWithPath(final Path sibling, final Path other) {
    return sibling.resolveSibling(other);
  }
}
