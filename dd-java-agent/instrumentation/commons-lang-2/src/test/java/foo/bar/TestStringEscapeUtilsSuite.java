package foo.bar;

import org.apache.commons.lang.StringEscapeUtils;

public class TestStringEscapeUtilsSuite {

  public static String escapeHtml(String input) {
    return StringEscapeUtils.escapeHtml(input);
  }
}
