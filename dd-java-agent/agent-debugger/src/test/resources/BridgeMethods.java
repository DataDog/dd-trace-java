public class BridgeMethods extends OtherClass<String> {
  public static String main() {
    OtherClass<String> bridgeMethods = new BridgeMethods();
    return bridgeMethods.process("world");
  }
  public String process(String param) {
    return "hello " + param;
  }
}

abstract class OtherClass<T> {
  public abstract T process(T param);
}
