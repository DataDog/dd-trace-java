package datadog.trace.bootstrap.instrumentation.buffer;

/**
 * Builds representative HTML documents for the RUM injection benchmarks. Each shape stresses a
 * different part of the streaming parser while keeping a comparable total size (~64 KiB).
 */
final class HtmlDocuments {
  private static final int TARGET_SIZE = 64 * 1024;

  private HtmlDocuments() {}

  static String build(String shape) {
    switch (shape) {
      case "SIMPLE":
        return simple();
      case "COMMENTS":
        return commentHeavy();
      case "SCRIPTS":
        return scriptHeavy();
      case "PLAIN_TEXT":
        return plainText();
      default:
        throw new IllegalArgumentException("Unknown shape: " + shape);
    }
  }

  private static String simple() {
    StringBuilder sb = new StringBuilder(TARGET_SIZE + 512);
    sb.append("<!DOCTYPE html><html><head>")
        .append("<meta charset=\"utf-8\"><title>Bench</title>")
        .append("<link rel=\"stylesheet\" href=\"/app.css\">")
        .append("</head><body>");
    appendBody(sb, "<p>Lorem ipsum dolor sit amet, consectetur adipiscing elit.</p>");
    sb.append("</body></html>");
    return sb.toString();
  }

  private static String commentHeavy() {
    StringBuilder sb = new StringBuilder(TARGET_SIZE + 512);
    // decoy end tag inside a comment must be ignored by the streaming parser.
    sb.append("<!DOCTYPE html><html><head><!-- </head> not the real one -->")
        .append("<meta charset=\"utf-8\">")
        .append("</head><body>");
    appendBody(sb, "<!-- a comment with < brackets and </head> decoys --><p>text</p>");
    sb.append("</body></html>");
    return sb.toString();
  }

  private static String scriptHeavy() {
    StringBuilder sb = new StringBuilder(TARGET_SIZE + 512);
    sb.append("<!DOCTYPE html><html><head>")
        .append("<script>var s = '</head>'; function f(a){ return a < 10; }</script>")
        .append("<style>.a{content:'</head>'}</style>")
        .append("</head><body>");
    appendBody(sb, "<script>for (var i=0;i<10;i++){ doWork(i); }</script>");
    sb.append("</body></html>");
    return sb.toString();
  }

  private static String plainText() {
    // no head/marker at all: worst case for the literal matcher (always buffering) and a good
    // throughput test for the streaming parser's data-state fast path.
    StringBuilder sb = new StringBuilder(TARGET_SIZE + 512);
    sb.append("<html><body>");
    appendBody(sb, "plain text with occasional < and > characters but no head tag ");
    sb.append("</body></html>");
    return sb.toString();
  }

  private static void appendBody(StringBuilder sb, String chunk) {
    while (sb.length() < TARGET_SIZE) {
      sb.append(chunk);
    }
  }
}
