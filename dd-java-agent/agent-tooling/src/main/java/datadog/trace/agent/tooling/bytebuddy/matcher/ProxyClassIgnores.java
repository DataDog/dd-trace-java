package datadog.trace.agent.tooling.bytebuddy.matcher;

/** Ignores various generated proxies based on well-known markers in their class names. */
public class ProxyClassIgnores {
  private ProxyClassIgnores() {}

  public static boolean isIgnored(String name) {
    for (int last = -1, idx; (idx = name.indexOf('$', last + 1)) >= 0; last = idx) {
      if (last < 0 && name.contains("CGLIB$$")) {
        // check this once
        return true;
      }
      if (idx == last + 1) {
        // skip the trie if consecutive $$ since, to be efficient, we can match prefixes from the
        // first dollar
        continue;
      }
      if (ProxyIgnoredClassNameTrie.apply(name, idx) == 1) {
        return true;
      }
    }
    return name.contains("javassist") || name.contains(".asm.");
  }
}
