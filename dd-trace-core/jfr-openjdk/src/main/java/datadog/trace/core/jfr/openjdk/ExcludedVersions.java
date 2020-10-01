package datadog.trace.core.jfr.openjdk;

import datadog.trace.core.DDTraceCoreInfo;

public class ExcludedVersions {

  public static void checkVersionExclusion() throws ClassNotFoundException {
    if (isVersionExcluded(DDTraceCoreInfo.JAVA_VERSION)) {
      throw new ClassNotFoundException("Excluded java version: " + DDTraceCoreInfo.JAVA_VERSION);
    }
  }

  static boolean isVersionExcluded(final String version) {
    final NumberTokenizer tokenizer = new NumberTokenizer(version);

    int major = tokenizer.nextNumber();
    if (major == 1) {
      major = tokenizer.nextNumber(); // ignore leading 1. for pre-Java9 JDKs
    }

    final int minor = tokenizer.nextNumber();
    final int update = tokenizer.nextNumber();

    // Java 9 and 10 throw seg fault on MacOS if events are used in premain.
    // Since these versions are not LTS we just disable profiling events for them.
    if (major == 9 || major == 10) {
      return true;
    }

    // Exclude 1.8.0_262 onwards due to https://bugs.openjdk.java.net/browse/JDK-8252904
    if (major == 8 && minor == 0 && update >= 262) {
      return true;
    }

    return false;
  }

  /** Simple number tokenizer that treats non-digit characters as delimiters. */
  private static class NumberTokenizer {
    private final String text;
    private int cursor;

    NumberTokenizer(final String text) {
      this.text = text;
    }

    /** @return next number from this tokenizer */
    public int nextNumber() {
      int number = 0;
      boolean parsingNumber = true;
      while (cursor < text.length()) {
        final char c = text.charAt(cursor);
        if (c < '0' || c > '9') {
          parsingNumber = false; // no more digits to add, continue to consume non-digits
        } else if (parsingNumber) {
          number = number * 10 + (c - '0');
        } else {
          break; // stop now we've reached another number, leave that for the next request
        }
        cursor++;
      }
      return number;
    }
  }
}
