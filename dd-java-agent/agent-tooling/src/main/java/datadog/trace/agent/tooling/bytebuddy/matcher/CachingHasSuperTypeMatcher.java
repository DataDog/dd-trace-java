package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.SafeHasSuperTypeMatcher.safeGetInterfaces;
import static datadog.trace.agent.tooling.bytebuddy.matcher.SafeHasSuperTypeMatcher.safeGetSuperClass;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import datadog.trace.agent.tooling.bytebuddy.DDCachingPoolStrategy.TypeCacheKey;
import datadog.trace.agent.tooling.bytebuddy.DDDescriptionStrategy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.HasSuperTypeMatcher;

public class CachingHasSuperTypeMatcher<T extends TypeDescription> extends HasSuperTypeMatcher<T> {
  static final int CONCURRENCY_LEVEL = 8;
  static final int CACHE_CAPACITY = 64;

  private volatile Cache<TypeCacheKey, Boolean> cache;

  private final ElementMatcher<? super TypeDescription.Generic> matcher;

  public CachingHasSuperTypeMatcher(final ElementMatcher<? super TypeDescription.Generic> matcher) {
    super(matcher);
    this.matcher = matcher;
  }

  @Override
  public boolean matches(final T target) {
    if (!(target instanceof DDDescriptionStrategy.TypeDescriptionWithTypeCacheKey)) {
      return super.matches(target);
    }

    // FIXME: this class makes incorrect assumptions about the classloader for parent
    // classes/interfaces.
    final TypeCacheKey.Partial partialKey =
        ((DDDescriptionStrategy.TypeDescriptionWithTypeCacheKey) target).getPartialKey();

    return recursiveMatchClass(target, partialKey, new HashSet<TypeDescription>());
  }

  boolean recursiveMatchClass(
      final TypeDefinition type,
      final TypeCacheKey.Partial partialKey,
      final Set<TypeDescription> previous) {
    if (type == null || TypeDescription.OBJECT.equals(type)) {
      return false; // Don't bother matching Object.
    } else if (!previous.add(type.asErasure())) { // Main type can be an interface.
      return false; // Avoids a life-lock when encountering a recursive type-definition.
    } else {
      final Boolean result;
      if ((result = evaluateType(partialKey, previous, type.asGenericType())) != null) {
        return result;
      }
      return recursiveMatchClass(safeGetSuperClass(type), partialKey, previous);
    }
  }

  boolean recursiveMatchInterface(
      final List<TypeDescription.Generic> target,
      final TypeCacheKey.Partial partialKey,
      final Set<TypeDescription> previous) {
    for (final TypeDescription.Generic interfaceType : target) {
      if (previous.add(interfaceType.asErasure())) {
        final Boolean result;
        if ((result = evaluateType(partialKey, previous, interfaceType)) != null) {
          return result;
        }
      }
    }
    return false;
  }

  private Boolean evaluateType(
      final TypeCacheKey.Partial partialKey,
      final Set<TypeDescription> previous,
      final TypeDescription.Generic type) {
    final TypeCacheKey key = partialKey.getKey(type.getTypeName());
    final Boolean result;
    if ((result = cache().getIfPresent(key)) != null) {
      return result;
    } else if (matcher.matches(type.asGenericType())) {
      cache().put(key, true);
      return true;
    } else if (recursiveMatchInterface(safeGetInterfaces(type), partialKey, previous)) {
      cache().put(key, true);
      return true;
    }
    cache().put(key, false);
    return null;
  }

  private Cache<TypeCacheKey, Boolean> cache() {
    if (cache == null) {
      synchronized (this) {
        if (cache == null) {
          cache =
              CacheBuilder.newBuilder()
                  .weakKeys()
                  .concurrencyLevel(CONCURRENCY_LEVEL)
                  .initialCapacity(CACHE_CAPACITY / 2)
                  .maximumSize(CACHE_CAPACITY)
                  .build();
        }
      }
    }
    return cache;
  }

  @Override
  public String toString() {
    return "cachingHasSuperType(" + matcher + ")";
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
      return matcher.equals(((CachingHasSuperTypeMatcher) other).matcher);
    }
  }

  @Override
  public int hashCode() {
    return 17 * 31 + matcher.hashCode();
  }
}
