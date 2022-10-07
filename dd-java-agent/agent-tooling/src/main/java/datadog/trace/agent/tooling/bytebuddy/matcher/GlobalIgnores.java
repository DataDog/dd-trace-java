package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.TargetSystemAwareMatcher.IAST_ENABLED_FLAG;

/**
 * Global ignores used by the agent.
 *
 * <p>There are two levels of ignores:
 *
 * <ul>
 *   <li>Ignore classes that are unsafe or pointless to transform. 'System' level classes like jvm
 *       classes or groovy classes, other tracers, debuggers, etc.
 *   <li>Ignore additional classes to minimize the number of classes we apply expensive matchers to.
 * </ul>
 */
public class GlobalIgnores {
  private GlobalIgnores() {}

  public static boolean isIgnored(String name, boolean skipAdditionalIgnores) {
    // ignored classes/packages are now maintained in the 'ignored_class_name.trie' resource
    // try to get value from cache otherwise use apply
    Integer allowCode = GlobalIgnoresCache.getAllowCode(name);
    switch (allowCode) {
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
      case IAST_ENABLED_FLAG:
        return false;
      default:
        break;
    }
    return false;
  }

  public static boolean isAdditionallyIgnored(String name) {
    return !isIgnored(name, true) && isIgnored(name, false);
  }
}
