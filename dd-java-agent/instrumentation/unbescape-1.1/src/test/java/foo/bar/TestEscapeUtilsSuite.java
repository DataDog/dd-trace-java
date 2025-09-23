package foo.bar;

import org.unbescape.html.HtmlEscape;
import org.unbescape.javascript.JavaScriptEscape;

public class TestEscapeUtilsSuite {

  public static String escapeHtml4Xml(String input) {
    return HtmlEscape.escapeHtml4Xml(input);
  }

  public static String escapeHtml4(String input) {
    return HtmlEscape.escapeHtml4(input);
  }

  public static String escapeHtml5(String input) {
    return HtmlEscape.escapeHtml5(input);
  }

  public static String escapeHtml5Xml(String input) {
    return HtmlEscape.escapeHtml5Xml(input);
  }

  public static String escapeJavaScript(String input) {
    return JavaScriptEscape.escapeJavaScript(input);
  }
}
