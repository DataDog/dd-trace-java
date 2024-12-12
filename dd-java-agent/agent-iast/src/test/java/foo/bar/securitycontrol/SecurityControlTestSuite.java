package foo.bar.securitycontrol;

public class SecurityControlTestSuite {

  public static boolean validateAll(String input) {
    return true; // dummy implementation
  }

  public static boolean validateAll(String input, String input2) {
    return true; // dummy implementation
  }

  public static boolean validate(String input) {
    return true; // dummy implementation
  }

  public static boolean validate(Object o, String input, String input2) {
    return true; // dummy implementation
  }

  public static String sanitize(String input) {
    return "Sanitized " + input;
  }
}
