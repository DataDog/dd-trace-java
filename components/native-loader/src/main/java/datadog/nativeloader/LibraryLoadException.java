package datadog.nativeloader;

/** Exception raised when NativeLoader fails to resolve or load a library */
public class LibraryLoadException extends Exception {
  static final String UNSUPPORTED_OS = "Unsupported OS";
  static final String UNSUPPORTED_ARCH = "Unsupported arch";

  private static final long serialVersionUID = 1L;

  public LibraryLoadException(String libName) {
    super(message(libName));
  }

  public LibraryLoadException(String libName, Throwable cause) {
    this(message(libName), cause.getMessage(), cause);
  }

  public LibraryLoadException(String libName, String message) {
    super(message(libName) + " - " + message);
  }

  public LibraryLoadException(String libName, String message, Throwable cause) {
    super(message(libName) + " - " + message, cause);
  }

  static final String message(String libName) {
    return "Unable to resolve library " + libName;
  }
}
