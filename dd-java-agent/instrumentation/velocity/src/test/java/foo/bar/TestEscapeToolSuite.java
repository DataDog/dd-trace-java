package foo.bar;

import org.apache.velocity.tools.generic.EscapeTool;

public class TestEscapeToolSuite {

  public static EscapeTool escapeTool = new EscapeTool();

  public static String html(String input) {
    return escapeTool.html(input);
  }

  public static String javascript(String input) {
    return escapeTool.javascript(input);
  }

  public static String url(String input) {
    return escapeTool.url(input);
  }

  public static String xml(String input) {
    return escapeTool.xml(input);
  }

  public static String sql(String input) {
    return escapeTool.sql(input);
  }
}
