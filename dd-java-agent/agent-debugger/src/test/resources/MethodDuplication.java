public class MethodDuplication {

  public static int invocations = 0;
  int f1(int value) {
    return value;
  }

  int f2(int value) {
    return value;
  }

  int fibonacci(int n) {
    if (n <= 1) return n;
    int minus2 = fibonacci(n - 2);
    int minus1 = fibonacci(n - 1);
    return minus2 + minus1;
  }
  int fibonacci(int n, String dummy) {
    invocations++;
    if (n <=1) return 1;
    int minus2 = fibonacci(n - 2);
    int minus1 = fibonacci(n - 1);
    return minus2 + minus1;
  }

  void empty() {

  }

  public static int main(String arg) {
    MethodDuplication test = new MethodDuplication();
    return test.fibonacci(Integer.valueOf(arg));
  }
}
