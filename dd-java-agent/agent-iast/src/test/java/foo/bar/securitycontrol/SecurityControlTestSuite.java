package foo.bar.securitycontrol;

public class SecurityControlTestSuite {
  public boolean validateAll(long input, String input2) {
    // dummy implementation
    return true;
  }

  public boolean validateAll(String input) {
    // dummy implementation
    return true;
  }

  public boolean validateAll(String input, String input2) {
    // dummy implementation
    return true;
  }

  public boolean validateAll(
      String input,
      String input2,
      String input3,
      String input4,
      String input5,
      String input6,
      String input7,
      String input8,
      String input9,
      String input10
  ) {
    // dummy implementation
    return true;
  }

  public boolean validateLong(long input, String input2) {
    // dummy implementation
    return true;
  }

  public boolean validateLong(String input, long input2) {
    // dummy implementation
    return true;
  }

  public boolean validateLong(long intput1, String input2, long input3) {
    // dummy implementation
    return true;
  }

  public boolean validateSelectedLong(long intput1) {
    // dummy implementation
    return true;
  }

  public boolean validateSelectedLong(long input1, long intput2) {
    // dummy implementation
    return true;
  }

  public boolean validate(String input) {
    // dummy implementation
    return true;
  }

  public boolean validate(Object o, String input, String input2) {
    // dummy implementation
    return true;
  }

  public int validateReturningInt(String input) {
    // dummy implementation
    return 1;
  }

  public int validateObject(Object input) {
    // dummy implementation
    return 1;
  }

  public String sanitize(String input) {
    return "Sanitized";
  }

  public Object sanitizeObject(String input) {
    return "Sanitized";
  }

  public String sanitizeInputs(String input, Object input2, int input3) {
    return "Sanitized";
  }

  public String sanitizeManyInputs(
      String input,
      String input2,
      String input3,
      String input4,
      String input5,
      String input6,
      String input7,
      String input8,
      String input9,
      String input10
  ) {
    return "Sanitized";
  }

  public int sanitizeInt(int input) {
    return input;
  }

  public long sanitizeLong(long input) {
    return input;
  }
}
