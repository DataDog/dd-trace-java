public class CapturedSnapshot03Oskar {
  public static int main(String arg) {
    String var1 = "var1";
    if (arg.equals("foo")) {
      String var2 = "var2";
      System.out.println(var2);
    } else {
      String var3 = "var3";
      System.out.println(var3);
      if (arg.equals(var3)) {
        String var4 = "var4";
        System.out.println(var4);
      }
      if (arg.equals(var1)) {
        return var3.length();
      }
    }
    String var5 = "var5";
    return var1.length();
  }
}
