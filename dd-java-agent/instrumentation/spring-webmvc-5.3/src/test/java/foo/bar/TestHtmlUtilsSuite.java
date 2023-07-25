package foo.bar;

import org.springframework.web.util.HtmlUtils;

public class TestHtmlUtilsSuite {

  public static String htmlEscape(String input) {
    return HtmlUtils.htmlEscape(input);
  }

  public static String htmlEscape(String input, String encoding) {
    return HtmlUtils.htmlEscape(input, encoding);
  }
}
