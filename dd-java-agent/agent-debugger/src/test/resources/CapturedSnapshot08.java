public class CapturedSnapshot08 {
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

  private static final CapturedSnapshot08 INSTANCE = new CapturedSnapshot08();

  public static int main(String arg) {
    return INSTANCE.doit(arg);
  }

  private int doit(String arg) {
    int var1 = 1;
    if (Integer.parseInt(arg) == 2) {
      var1 = 2;
      return var1;
    }
    var1 = 3;
    return var1;
  }
}
