public class CapturedSnapshot06Oskar {
  public static int main(String arg) {
    try {
      throw new RuntimeException("foo");
    } catch (RuntimeException rte) {
      System.out.println("rte = " + rte.getMessage());
    }
    return arg.length();
  }
}
