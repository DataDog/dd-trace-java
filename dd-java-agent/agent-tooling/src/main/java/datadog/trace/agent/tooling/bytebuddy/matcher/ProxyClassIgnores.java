package datadog.trace.agent.tooling.bytebuddy.matcher;

/** Ignores various generated proxies based on well-known markers in their class names. */
public class ProxyClassIgnores {
  private ProxyClassIgnores() {}

  public static boolean isIgnored(String name) {
    if (name.indexOf('$') > -1) {
      if (name.contains("$JaxbAccessor")
          || name.contains("CGLIB$$")
          || name.contains("$__sisu")
          || name.contains("$$EnhancerByGuice$$")
          || name.contains("$$EnhancerByProxool$$")
          || name.contains("$$$view")
          || name.contains("$$$endpoint") // jboss mdb proxies
          || name.contains("$$_Weld")
          || name.contains("_$$_jvst")) {
        return true;
      }
    }
    return name.contains("javassist") || name.contains(".asm.");
  }
}
