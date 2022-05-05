package datadog.trace.agent.tooling.bytebuddy.matcher;

public class GlobalIgnores {
  private GlobalIgnores() {}

  public static boolean isIgnored(String name, boolean skipAdditionalIgnores) {
    // ignored classes/packages are now maintained in the 'ignored_class_name.trie' resource
    switch (IgnoredClassNameTrie.apply(name)) {
      case 0:
        return false; // global allow
      case 1:
        return true; // system-level ignore
      case 2:
        return !skipAdditionalIgnores;
      case 3:
        return name.endsWith("Proxy");
      case 4:
        return !name.endsWith("HttpMessageConverter");
      default:
        break;
    }
    return false;
  }

  public static boolean isAdditionallyIgnored(String name) {
    return !isIgnored(name, true) && isIgnored(name, false);
  }
}
