import java.util.List;
import java.util.ArrayList;

public class CapturedSnapshot05 {

  void triggerUncaughtException() {
    throw new IllegalStateException("oops");
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
    if ("triggerUncaughtException".equals(arg)) {
      cs5.triggerUncaughtException();
    } else if ("triggerCaughtException".equals(arg)) {
      return cs5.triggerCaughtException();
    }
    return 0;
  }

}
