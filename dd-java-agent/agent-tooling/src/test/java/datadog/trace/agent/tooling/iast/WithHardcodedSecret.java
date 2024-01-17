package datadog.trace.agent.tooling.iast;

public class WithHardcodedSecret {

  private static final int NUMBER = 1;
  private static final String FOO = "foo";

  public static String getSecret() {
    return "AGE-SECRET-KEY-1QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ";
  }

  public static String getSecret2() {
    return "ghu_39GyMbaIlk2UMGTkC9WCDlpe9AjRNZa1WZQW";
  }
}
