package foo.bar;

import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.JavaScriptUtils;

public class TestEscapeUtilsSuite {

  public static String htmlEscape(String input) {
    return HtmlUtils.htmlEscape(input);
  }

  public static String htmlEscape(String input, String encoding) {
    return HtmlUtils.htmlEscape(input, encoding);
  }

  public static String javaScriptEscape(String input) {
    return JavaScriptUtils.javaScriptEscape(input);
  }
}
