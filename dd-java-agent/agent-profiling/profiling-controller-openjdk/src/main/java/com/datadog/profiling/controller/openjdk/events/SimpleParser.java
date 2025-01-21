package com.datadog.profiling.controller.openjdk.events;

final class SimpleParser {
  private final String line;

  private int pos;

  public SimpleParser(String line) {
    this.line = line;
    this.pos = 0;
  }

  void skipToNextValue(boolean acceptDash) {
    int limit = line.length();
    while (pos < limit && isDelimiter(line.charAt(pos), acceptDash)) {
      pos++;
    }
  }

  void skipValue(boolean acceptDash) {
    int limit = line.length();
    char c = 0;
    while (pos < limit && !isDelimiter(line.charAt(pos), acceptDash)) {
      pos++;
    }
  }

  int getPosition() {
    return pos;
  }

  void setPosition(int pos) {
    this.pos = pos;
  }

  long nextLongValue(int base) {
    if (base != 10 && base != 16) {
      throw new UnsupportedOperationException("Unsupported base: " + base);
    }
    int limit = line.length();
    skipToNextValue(true);
    if (pos == limit) {
      pos = -1;
      return -1;
    }
    long val = 0;
    char c = 0;
    while (pos < limit && !isDelimiter(c = line.charAt(pos), true)) {
      if (c < '0' || c > '9') {
        if (base == 10 || (c < 'a' || c > 'f')) {
          skipValue(true);
          return -1;
        }
      }
      int digit = c >= 'a' ? 10 + (c - 'a') : (c - '0');
      val = val * base + digit;
      pos++;
    }
    return val;
  }

  String nextStringValue() {
    int limit = line.length();
    skipToNextValue(false);
    if (pos == limit) {
      pos = -1;
      return null;
    }
    long val = 0;
    StringBuilder sb = new StringBuilder();
    char c = 0;
    while (pos < limit && !isDelimiter(c = line.charAt(pos), false)) {
      sb.append(c);
      pos++;
    }
    return sb.toString();
  }

  String slurpStringValue() {
    int limit = line.length();
    skipToNextValue(false);
    if (pos == limit) {
      pos = -1;
      return null;
    }
    return line.substring(pos).trim();
  }

  private static boolean isDelimiter(char c, boolean acceptDash) {
    return c == ' ' || (acceptDash && c == '-');
  }
}
