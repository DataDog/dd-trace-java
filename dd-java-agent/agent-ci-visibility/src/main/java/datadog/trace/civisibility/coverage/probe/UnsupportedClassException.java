package datadog.trace.civisibility.coverage.probe;

/** Thrown when the class file patcher leaves a class to the ASM transformer. */
final class UnsupportedClassException extends RuntimeException {

  static final String MALFORMED = "malformed class file";
  static final String TYPE_ANNOTATIONS = "type annotations in code";
  static final String OTHER_CODE_ATTRIBUTE = "other code attribute";
  static final String LINE_OFF_INSTRUCTION = "line entry off an instruction";
  static final String LINE_ZERO = "line number 0";
  static final String LOCAL_VARIABLE_OFF_INSTRUCTION = "local variable range off an instruction";
  static final String UNKNOWN_FRAME = "unknown stack map frame";
  static final String CODE_TOO_LONG = "code over 32 KB";
  static final String SAME_FRAME_OFFSET = "two frames at one offset";

  /** One of the constants above, to count fallbacks by. */
  final String reason;

  UnsupportedClassException(String reason) {
    super(reason, null, false, false);
    this.reason = reason;
  }
}
