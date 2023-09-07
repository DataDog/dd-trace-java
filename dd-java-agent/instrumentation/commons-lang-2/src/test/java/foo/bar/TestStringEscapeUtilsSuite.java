package foo.bar;

import org.apache.commons.lang.StringEscapeUtils;

public class TestStringEscapeUtilsSuite {

  public static String escapeHtml(String input) {
    return StringEscapeUtils.escapeHtml(input);
  }

  public static String escapeJava(String input) {
    return StringEscapeUtils.escapeJava(input);
  }

  public static String escapeJavaScript(String input) {
    return StringEscapeUtils.escapeJavaScript(input);
  }

  public static String escapeXml(String input) {
    return StringEscapeUtils.escapeXml(input);
  }

  public static String escapeSql(String input) {
    return StringEscapeUtils.escapeSql(input);
  }
}
