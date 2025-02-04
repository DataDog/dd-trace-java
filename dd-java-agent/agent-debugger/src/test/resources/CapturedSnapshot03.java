public class CapturedSnapshot03 {

  int f1(int value) {
    return value; // beae1817-f3b0-4ea8-a74f-000000000001
  }

  int f2(int value) {
    return value;
  }

  void empty() {

  }

  public static int main(String arg) {
    CapturedSnapshot03 cs3 = new CapturedSnapshot03();
    cs3.empty();
    return cs3.f1(31) + cs3.f2(17);
  }
}
