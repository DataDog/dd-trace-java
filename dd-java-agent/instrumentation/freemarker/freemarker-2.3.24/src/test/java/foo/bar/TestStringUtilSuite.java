package foo.bar;

import freemarker.template.utility.StringUtil;

public class TestStringUtilSuite {

  public static String HTMLEnc(String input) {
    return StringUtil.HTMLEnc(input);
  }

  public static String XMLEnc(String input) {
    return StringUtil.XMLEnc(input);
  }

  public static String XHTMLEnc(String input) {
    return StringUtil.XHTMLEnc(input);
  }

  public static String javaStringEnc(String input) {
    return StringUtil.javaStringEnc(input);
  }

  public static String javaScriptStringEnc(String input) {
    return StringUtil.javaScriptStringEnc(input);
  }

  public static String jsonStringEnc(String input) {
    return StringUtil.jsonStringEnc(input);
  }
}
