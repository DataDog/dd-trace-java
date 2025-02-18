public class CapturedSnapshot01 {
  public static int main(String arg) {
    int var1 = 1;
    if (Integer.parseInt(arg) == 2) {
      var1 = 2;
      return var1;
    }
    var1 = 3; // beae1817-f3b0-4ea8-a74f-000000000001
    return var1; // beae1817-f3b0-4ea8-a74f-000000000002
  }
}
