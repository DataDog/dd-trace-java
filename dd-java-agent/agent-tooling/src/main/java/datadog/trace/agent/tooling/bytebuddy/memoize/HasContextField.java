package datadog.trace.agent.tooling.bytebuddy.memoize;

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.BitSet;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Matches types that should have context-store fields injected. */
final class HasContextField extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
  private final ElementMatcher<TypeDescription> storeMatcher;
  private final ElementMatcher<TypeDescription> skipMatcher;
  private final BitSet skippableStoreIds = new BitSet();

  HasContextField(ElementMatcher<TypeDescription> storeMatcher) {
    this(storeMatcher, null);
  }

  HasContextField(
      ElementMatcher<TypeDescription> storeMatcher, ElementMatcher<TypeDescription> skipMatcher) {
    this.storeMatcher = storeMatcher;
    this.skipMatcher = skipMatcher;
  }

  @Override
  protected boolean doMatch(TypeDescription target) {
    // match first type in the hierarchy which injects this store, that isn't skipped
    return storeMatcher.matches(target)
        && (null == skipMatcher || !skipMatcher.matches(target))
        && (!storeMatcher.matches(target.getSuperClass().asErasure()));
  }

  void maybeSkip(int contextStoreId) {
    skippableStoreIds.set(contextStoreId);
  }

  boolean hasSuperStore(TypeDescription target, BitSet weakStoreIds) {
    TypeDescription superTarget = target.getSuperClass().asErasure();
    boolean hasSuperStore = storeMatcher.matches(superTarget);
    if (hasSuperStore) {
      // report if super-class was skipped from field-injection
      if (null != skipMatcher && skipMatcher.matches(superTarget)) {
        weakStoreIds.or(skippableStoreIds);
      }
    }
    return hasSuperStore;
  }

  /** Matches types that would have had fields injected, but were explicitly excluded. */
  static final class Skip extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
    private final ExcludeFilter.ExcludeType excludeType;

    Skip(ExcludeFilter.ExcludeType excludeType) {
      this.excludeType = excludeType;
    }

    @Override
    protected boolean doMatch(TypeDescription target) {
      return ExcludeFilter.exclude(excludeType, target.getName());
    }
  }
}
