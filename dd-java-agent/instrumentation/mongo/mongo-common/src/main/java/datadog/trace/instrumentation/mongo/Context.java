package datadog.trace.instrumentation.mongo;

final class Context {

  private static final int MAX_DEPTH = 64;
  private static final int MAX_SEQUENCE_LENGTH = 256;

  private final StringBuilder buffer = new StringBuilder();

  // specifies the depth below which everything must be discarded,
  // e.g. because we're inside an $in clause we want to collapse
  private int discardDepth = MAX_DEPTH;
  private int keepDepth = MAX_DEPTH;
  private int depth;

  // tracks sequence element counts at each depth level to limit long arrays
  private final int[] sequenceCounts = new int[MAX_DEPTH];

  public void discardSubTree() {
    if (depth < discardDepth) {
      this.discardDepth = depth;
    }
  }

  public void keepSubTree() {
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
      discardDepth = MAX_DEPTH;
    }
    if (keepDepth == depth) {
      keepDepth = MAX_DEPTH;
    }
  }

  public void startArray() {
    if (depth < MAX_DEPTH) {
      sequenceCounts[depth] = 0;
    }
    // note: nothing to do at the end of the array
  }

  /**
   * Signals that a new element is being processed in the current array. Returns true if this
   * element should be written, false if the sequence limit has been reached.
   */
  public boolean nextSequenceElement() {
    // checking depth just to make sure we don't access the array out of bounds, but it should never
    // be the case since we stop processing the document before reaching this code.
    if (depth < MAX_DEPTH) {
      sequenceCounts[depth]++;
      return sequenceCounts[depth] <= MAX_SEQUENCE_LENGTH;
    }
    return false; // theoretically unreachable
  }

  public boolean disableObfuscation() {
    return depth > keepDepth;
  }

  public boolean ignoreSubTree() {
    return depth > discardDepth;
  }

  private boolean tooDeep() {
    // this means we are parsing a huge document, which we will truncate anyway
    // note that MongoDB sets a default max nested depth of 100, and MongoDB users
    // are generally advised to avoid deep nesting for the sake of database performance
    return depth >= MAX_DEPTH;
  }

  private boolean shouldWrite() {
    return !tooDeep() && (depth > keepDepth || depth < discardDepth + 1);
  }

  public void write(char symbol) {
    if (shouldWrite()) {
      buffer.append(symbol);
    }
  }

  public void write(long number) {
    if (shouldWrite()) {
      buffer.append(number);
    }
  }

  public void write(boolean value) {
    if (shouldWrite()) {
      buffer.append(value);
    }
  }

  public void write(String string) {
    if (shouldWrite()) {
      buffer.append(string);
    }
  }

  public void clear() {
    buffer.setLength(0);
    depth = 0;
    discardDepth = MAX_DEPTH;
    keepDepth = MAX_DEPTH;
  }

  public String asString() {
    return buffer.toString();
  }
}
