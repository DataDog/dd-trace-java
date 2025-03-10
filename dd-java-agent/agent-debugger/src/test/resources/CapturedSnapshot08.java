import java.util.Optional;

public class CapturedSnapshot08 {
  public static int main(String arg) {
    return INSTANCE.doit(arg);
  }

  private int doit(String arg) { // beae1817-f3b0-4ea8-a74f-000000000002
    int var1 = 1; // beae1817-f3b0-4ea8-a74f-000000000001
    if (Integer.parseInt(arg) == 2) {
      var1 = 2;
      return var1;
    }
    var1 = 3; // beae1817-f3b0-4ea8-a74f-000000000003, beae1817-f3b0-4ea8-a74f-000000000004
    return var1;
  }

  static class Type3 {
    final String msg;
    Type3(String msg) {
      this.msg = msg;
    }
  }

  static final class Type2 {
    final Type3 fld;
    Type2(Type3 fld) {
      this.fld = fld;
    }
  }

  static final class Type1 {
    final Type2 fld;
    Type1(Type2 fld) {
      this.fld = fld;
    }
  }

  private int fld = 11;
  private Type1 typed = new Type1(new Type2(new Type3("hello")));
  private Type1 nullTyped = new Type1(null);
  private Optional<String> maybeStr = Optional.of("maybe foo");

  private static final CapturedSnapshot08 INSTANCE = new CapturedSnapshot08();
}
