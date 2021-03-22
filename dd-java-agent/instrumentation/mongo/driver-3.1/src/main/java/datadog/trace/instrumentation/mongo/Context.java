package datadog.trace.instrumentation.mongo;

final class Context {

  private final StringBuilder buffer = new StringBuilder();

  // specifies the depth below which everything must be discarded,
  // e.g. because we're inside an $in clause we want to collapse
  private int discardDepth = 64;
  private int keepDepth = 64;
  private int depth;

  void discardSubTree() {
    if (depth < discardDepth) {
      this.discardDepth = depth;
    }
  }

  void keepSubTree() {
    if (depth < keepDepth) {
      this.keepDepth = depth;
    }
  }

  public void startDocument() {
    ++depth;
  }

  public void endDocument() {
    --depth;
    if (discardDepth == depth) {
      discardDepth = 64;
    }
    if (keepDepth == depth) {
      keepDepth = 64;
    }
  }

  boolean disableObfuscation() {
    return depth > keepDepth;
  }

  boolean ignoreSubTree() {
    return depth > discardDepth;
  }

  private boolean tooDeep() {
    // this means we are parsing a huge document, which we will truncate anyway
    // note that MongoDB sets a default max nested depth of 100, and MongoDB users
    // are generally advised to avoid deep nesting for the sake of database performance
    return depth >= 64;
  }

  private boolean shouldWrite() {
    return !tooDeep() && (depth > keepDepth || depth < discardDepth + 1);
  }

  void write(char symbol) {
    if (shouldWrite()) {
      buffer.append(symbol);
    }
  }

  void write(long number) {
    if (shouldWrite()) {
      buffer.append(number);
    }
  }

  void write(boolean value) {
    if (shouldWrite()) {
      buffer.append(value);
    }
  }

  void write(String string) {
    if (shouldWrite()) {
      buffer.append(string);
    }
  }

  void clear() {
    buffer.setLength(0);
    depth = 0;
  }

  String asString() {
    return buffer.toString();
  }
}
