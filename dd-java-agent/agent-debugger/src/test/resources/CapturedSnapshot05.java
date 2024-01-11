import java.util.List;
import java.util.ArrayList;

public class CapturedSnapshot05 {

  void triggerUncaughtException() {
    throw new CustomException("oops", "I did it again");
  }

  int triggerCaughtException() {
    try {
      throw new IllegalStateException("oops");
    } catch (IllegalStateException ex) {
      return 42;
    }
  }

  public static int main(String arg) {
    CapturedSnapshot05 cs5 = new CapturedSnapshot05();
    long before = System.currentTimeMillis();
    if ("triggerUncaughtException".equals(arg)) {
      cs5.triggerUncaughtException();
    } else if ("triggerCaughtException".equals(arg)) {
      return cs5.triggerCaughtException();
    }
    long after = System.currentTimeMillis();
    System.out.println(after-before);
    return 0;
  }

  public static class CustomException extends RuntimeException {
    private final String additionalMsg;

    public CustomException(String message, String additionalMsg) {
      super(message);
      this.additionalMsg = additionalMsg;
    }
  }
}
