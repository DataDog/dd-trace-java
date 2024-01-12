package foo.bar;

public class WithHardcodedSecret {

  private static final String FOO = "foo";

  public static String getSecret() {
    return "AGE-SECRET-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ";
  }
}
