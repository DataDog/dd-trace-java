package datadog.trace.agent.tooling.bytebuddy.matcher;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Global ignores matcher used by the agent.
 *
 * <p>This matcher services two main purposes:
 *
 * <ul>
 *   <li>Ignore classes that are unsafe or pointless to transform. 'System' level classes like jvm
 *       classes or groovy classes, other tracers, debuggers, etc.
 *   <li>Ignore additional classes to minimize the number of classes we apply expensive matchers to.
 * </ul>
 */
public class GlobalIgnoresMatcher<T extends TypeDescription>
    extends ElementMatcher.Junction.ForNonNullValues<T> {

  public static <T extends TypeDescription> GlobalIgnoresMatcher<T> globalIgnoresMatcher(
      final boolean skipAdditionalLibraryMatcher) {
    return new GlobalIgnoresMatcher<>(skipAdditionalLibraryMatcher);
  }

  private final boolean skipAdditionalLibraryMatcher;

  private GlobalIgnoresMatcher(final boolean skipAdditionalLibraryMatcher) {
    this.skipAdditionalLibraryMatcher = skipAdditionalLibraryMatcher;
  }

  /**
   * Be very careful about the types of matchers used in this section as they are called on every
   * class load, so they must be fast. Generally speaking try to only use name matchers as they
   * don't have to load additional info.
   *
   * @see DDRediscoveryStrategy#shouldRetransformBootstrapClass(String)
   */
  @Override
  protected boolean doMatch(final T target) {
    return isIgnored(target.getActualName(), skipAdditionalLibraryMatcher);
  }

  private static boolean isIgnored(String name, boolean skipAdditionalIgnores) {
    switch (IgnoresTrie.INSTANCE.applyAsInt(name)) {
      case 0:
        return false; // global allow
      case 1:
      case -1:
        return true; // system-level ignore
      case 2:
      case -2:
        return !skipAdditionalIgnores;
      case 3:
      case -3:
        return name.endsWith("Proxy");
      case 4:
      case -4:
        return !name.endsWith("HttpMessageConverter");
      default:
        break;
    }

    if (name.indexOf('$') > -1) {
      if (name.contains("$JaxbAccessor")
          || name.contains("CGLIB$$")
          || name.contains("$__sisu")
          || name.contains("$$EnhancerByGuice$$")
          || name.contains("$$EnhancerByProxool$$")) {
        return true;
      }
    }
    if (name.contains("javassist") || name.contains(".asm.")) {
      return true;
    }

    return false;
  }

  public static boolean isGloballyIgnored(String name) {
    return isIgnored(name, false);
  }

  public static boolean isAdditionallyIgnored(String name) {
    return !isIgnored(name, true) && isIgnored(name, false);
  }

  @Override
  public String toString() {
    return "globalIgnoresMatcher(" + skipAdditionalLibraryMatcher + ")";
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    } else if (other == null) {
      return false;
    } else if (getClass() != other.getClass()) {
      return false;
    } else {
      return skipAdditionalLibraryMatcher
          == ((GlobalIgnoresMatcher) other).skipAdditionalLibraryMatcher;
    }
  }

  @Override
  public int hashCode() {
    return 17 * 31 + (skipAdditionalLibraryMatcher ? 1231 : 1237);
  }
}
