package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.MalformedURLException;
import java.net.URL;
import org.junit.jupiter.api.Test;

public final class PathLocationHelperTest {
  @Test
  public void happyPath() throws MalformedURLException {
    URL expected = new URL("http://localhost");

    PathLocator pathLocator = (comp, path) -> expected;
    PathLocatorHelper helper = new PathLocatorHelper("test", pathLocator);

    URL result = helper.locate(null, "path");
    assertSame(expected, result);
  }

  @Test
  public void throwingException() {
    Exception expectedCause = new IllegalStateException("wrong!");
    PathLocator throwingPathLocator =
        (comp, path) -> {
          throw expectedCause;
        };

    PathLocatorHelper helper = new PathLocatorHelper("test", throwingPathLocator);

    // on exception, PathLocator returns null, but stores the exception for use when tryThrow is
    // called later
    URL result = helper.locate(null, "path");
    assertNull(result);

    try {
      // should throw, since an exception occurred earlier
      helper.tryThrow();

      fail("should raise a LibraryLoadException");
    } catch (LibraryLoadException e) {
      assertSame(expectedCause, e.getCause());
    }
  }

  @Test
  public void throwingLibraryLoadException() {
    Exception expectedCause = new LibraryLoadException("test", "wrong!");
    PathLocator throwingPathLocator =
        (comp, path) -> {
          throw expectedCause;
        };

    PathLocatorHelper helper = new PathLocatorHelper("test", throwingPathLocator);

    // on exception, PathLocator returns null, but stores the exception for use when tryThrow is
    // called later
    URL result = helper.locate(null, "path");
    assertNull(result);

    try {
      // should throw, since an exception occurred earlier
      helper.tryThrow();

      fail("should raise a LibraryLoadException");
    } catch (LibraryLoadException e) {
      assertSame(expectedCause, e);
    }
  }

  @Test
  public void throwingMultipleExceptions() {
    Exception firstCause = new IllegalStateException("wrong!");
    Exception secondCause = new IllegalStateException("wrong again!");

    PathLocator throwingPathLocator =
        (comp, path) -> {
          if (path.equals("firstPath")) {
            throw firstCause;
          } else {
            throw secondCause;
          }
        };

    // on exception, PathLocator returns null, but stores the first exception
    PathLocatorHelper helper = new PathLocatorHelper("test", throwingPathLocator);
    URL firstResult = helper.locate(null, "firstPath");
    assertNull(firstResult);

    // still retgurns null, but doesn't store exception
    URL secondResult = helper.locate(null, "secondPath");
    assertNull(secondResult);

    try {
      helper.tryThrow();

      fail("should raise a LibraryLoadException");
    } catch (LibraryLoadException e) {
      // expect just the first cause to be captured
      assertSame(firstCause, e.getCause());
    }
  }
}
