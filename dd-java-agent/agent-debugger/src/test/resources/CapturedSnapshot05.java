import java.io.FileNotFoundException;
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

  int triggerSwallowedException(int arg) {
    try {
      if (arg == 0)
        throw new IllegalStateException("oops");
      if (arg == 1)
        throw new IllegalArgumentException("nope!");
      throw new FileNotFoundException("not there");
    } catch (IllegalStateException ex) {
      // swallowed with empty catch block
    } catch (IllegalArgumentException ex) {
      ex.printStackTrace();
      return 0;
    } catch (Throwable ex) {
      return -1;
    }
    return 42;
  }

  public static int main(String arg) {
    CapturedSnapshot05 cs5 = new CapturedSnapshot05();
    long before = System.currentTimeMillis();
    if ("triggerUncaughtException".equals(arg)) {
      cs5.triggerUncaughtException();
    } else if ("triggerCaughtException".equals(arg)) {
      return cs5.triggerCaughtException();
    } else if ("triggerSwallowedException".equals(arg)) {
      cs5.triggerSwallowedException(0);
      cs5.triggerSwallowedException(1);
      return cs5.triggerSwallowedException(2);
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
