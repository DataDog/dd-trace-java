package datadog.trace.agent.tooling.bytebuddy.matcher;

public class ProxyIgnores {
  private ProxyIgnores() {}

  public static boolean isIgnored(String name) {
    if (name.indexOf('$') > -1) {
      if (name.contains("$JaxbAccessor")
          || name.contains("CGLIB$$")
          || name.contains("$__sisu")
          || name.contains("$$EnhancerByGuice$$")
          || name.contains("$$EnhancerByProxool$$")
          || name.contains("$$_WeldClientProxy")) {
        return true;
      }
    }
    return name.contains("javassist") || name.contains(".asm.");
  }
}
