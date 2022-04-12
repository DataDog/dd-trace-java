package datadog.trace.agent.tooling.bytebuddy.matcher;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * An element matcher that matches generated proxy classes that shouldn't be transformed.
 *
 * <p>Separate from the global ignores matcher because it uses non-trivial 'contains' checks.
 */
class ProxyClassMatcher<T extends TypeDescription>
    extends ElementMatcher.Junction.ForNonNullValues<T> {

  @Override
  protected boolean doMatch(final T target) {
    final String name = target.getActualName();
    final int firstDollar = name.indexOf('$');
    if (firstDollar > -1) {
      if (name.contains("$JaxbAccessor")
          || name.contains("CGLIB$$")
          || name.contains("$__sisu")
          || name.contains("$$EnhancerByGuice$$")
          || name.contains("$$EnhancerByProxool$$")) {
        return true;
      }
    }
    return name.contains("javassist") || name.contains(".asm.");
  }
}
