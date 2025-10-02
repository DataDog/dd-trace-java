package foo.bar;

import org.apache.commons.lang3.StringEscapeUtils;

public class TestStringEscapeUtilsSuite {

  public static String escapeHtml3(String input) {
    return StringEscapeUtils.escapeHtml3(input);
  }

  public static String escapeHtml4(String input) {
    return StringEscapeUtils.escapeHtml4(input);
  }

  public static String escapeEcmaScript(String input) {
    return StringEscapeUtils.escapeEcmaScript(input);
  }

  public static String escapeXml10(String input) {
    return StringEscapeUtils.escapeXml10(input);
  }

  public static String escapeXml11(String input) {
    return StringEscapeUtils.escapeXml11(input);
  }

  public static String escapeJson(String input) {
    return StringEscapeUtils.escapeJson(input);
  }
}
