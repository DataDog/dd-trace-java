import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

public class CapturedSnapshot02 {
  private final List<String> list;
  private final String str;
  private Object obj;
  private CapturedSnapshot02 cause;

  CapturedSnapshot02() {
    this(new Object().toString(), createObject());
  }

  CapturedSnapshot02(Throwable throwable) {
    this("", new Object());
    Throwable nested = throwable.getCause();
    if (nested != null) {
      this.cause = new CapturedSnapshot02(nested);
    }
    obj = new Object();
  }

  CapturedSnapshot02(String str, Object obj) {
    this.list = new ArrayList<>();
    this.str = str;
    this.obj = obj;
  }

  static Object createObject() {
    return new Object();
  }

  void f() {
      try {
        System.currentTimeMillis();
      } catch (RuntimeException ex) {

      }
  }

  int synchronizedBlock(int input) {
    int count = input;
    synchronized (this) {
      for (int i = 0; i < 10; i++) {
        count += i;
      }
    }
    return count;
  }

  public static int main(String arg) throws IOException {
    if (arg.equals("f")) {
      CapturedSnapshot02 c1 = new CapturedSnapshot02();
      c1.f();
      return 42;
    }
    if (arg.equals("init")) {
      CapturedSnapshot02 c1 = new CapturedSnapshot02(new Exception());
      return 42;
    }
    if (arg.equals("synchronizedBlock")) {
      CapturedSnapshot02 c2 = new CapturedSnapshot02();
      return c2.synchronizedBlock(31);
    }
    return 0;
  }
}
