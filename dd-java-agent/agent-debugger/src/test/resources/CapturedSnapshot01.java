public class CapturedSnapshot01 {
  public static int main(String arg) {
    int var1 = 1;
    if (Integer.parseInt(arg) == 2) {
      var1 = 2;
      return var1;
    }
    var1 = 3;
    return var1;
  }
}
