package datadog.nativeloader;

import java.net.URL;

/**
 * Helper {@link PathLocator} that can be useful when doing multiple look-ups. PathLocatorHelper can
 * be used to wrap another {@link PathLocator} and make the exception handling easier.
 */
public final class PathLocatorHelper implements PathLocator {
  final String libName;
  final PathLocator locator;

  private Throwable firstCause;

  public PathLocatorHelper(String libName, PathLocator locator) {
    this.libName = libName;
    this.locator = locator;
  }

  @Override
  public URL locate(String component, String path) {
    try {
      return this.locator.locate(component, path);
    } catch (Throwable t) {
      if (this.firstCause == null) this.firstCause = t;
      return null;
    }
  }

  /** Raises a LibraryLoadException if an exception occurred during a prior call to locate */
  public void tryThrow() throws LibraryLoadException {
    if (this.firstCause instanceof LibraryLoadException) {
      throw (LibraryLoadException) this.firstCause;
    } else if (this.firstCause != null) {
      throw new LibraryLoadException(this.libName, this.firstCause);
    }
  }
}
