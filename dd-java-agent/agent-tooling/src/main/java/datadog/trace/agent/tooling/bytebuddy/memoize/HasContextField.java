package datadog.trace.agent.tooling.bytebuddy.memoize;

import java.util.BitSet;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

final class HasContextField extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
  private final ExpectContextField expectMatcher;
  private final ElementMatcher<TypeDescription> excludeMatcher;
  private final BitSet contextStoreIds = new BitSet();

  HasContextField(ExpectContextField expectMatcher) {
    this(expectMatcher, null);
  }

  HasContextField(
      ExpectContextField expectMatcher, ElementMatcher<TypeDescription> excludeMatcher) {
    this.expectMatcher = expectMatcher;
    this.excludeMatcher = excludeMatcher;
  }

  @Override
  protected boolean doMatch(TypeDescription target) {
    return expectMatcher.matches(target)
        && (null == excludeMatcher || !excludeMatcher.matches(target));
  }

  void maybeExclude(int contextStoreId) {
    contextStoreIds.set(contextStoreId);
  }

  boolean hasSuperStore(TypeDescription target, BitSet excludedStoreIds) {
    TypeDescription superTarget = target.getSuperClass().asErasure();
    if (expectMatcher.hasContextStore(superTarget)) {
      if (null != excludeMatcher && excludeMatcher.matches(superTarget)) {
        synchronized (excludedStoreIds) {
          excludedStoreIds.or(contextStoreIds);
        }
      }
      return true;
    }
    return false;
  }
}
