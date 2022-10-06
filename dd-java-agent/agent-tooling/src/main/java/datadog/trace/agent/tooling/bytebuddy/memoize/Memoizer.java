package datadog.trace.agent.tooling.bytebuddy.memoize;

import datadog.trace.agent.tooling.bytebuddy.TypeInfoCache;
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.function.Function;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressForbidden
@SuppressWarnings("rawtypes")
final class Memoizer {
  private static final Logger log = LoggerFactory.getLogger(Memoizer.class);

  enum MatcherKind {
    ANNOTATION,
    FIELD,
    METHOD,
    CLASS,
    INTERFACE,
    TYPE // i.e. class or interface
  }

  private static final BitSet NO_MATCH = new BitSet(0);

  private static final int SIZE_HINT = 320; // estimated number of matchers

  private static final BitSet annotationMatcherIds = new BitSet(SIZE_HINT);
  private static final BitSet fieldMatcherIds = new BitSet(SIZE_HINT);
  private static final BitSet methodMatcherIds = new BitSet(SIZE_HINT);
  private static final BitSet classMatcherIds = new BitSet(SIZE_HINT);
  private static final BitSet interfaceMatcherIds = new BitSet(SIZE_HINT);
  private static final BitSet inheritedMatcherIds = new BitSet(SIZE_HINT);

  static final List<ElementMatcher> matchers = new ArrayList<>();

  private static final DDCache<ElementMatcher, MemoizingMatcher> memoizingMatcherCache =
      DDCaches.newFixedSizeIdentityCache(8);

  private static final Function<ElementMatcher, MemoizingMatcher> newMemoizingMatcher =
      new Function<ElementMatcher, MemoizingMatcher>() {
        @Override
        public MemoizingMatcher apply(ElementMatcher input) {
          return new MemoizingMatcher(matchers.size());
        }
      };

  private static final NoMatchCache noMatchCache =
      new NoMatchCache(Config.get().getResolverNoMatchCacheSize());

  private static final TypeInfoCache<BitSet> memos =
      new TypeInfoCache<>(Config.get().getResolverMemoPoolSize());

  private static final ThreadLocal<Map<String, BitSet>> localMemos =
      new ThreadLocal<Map<String, BitSet>>() {
        @Override
        protected Map<String, BitSet> initialValue() {
          return new HashMap<>();
        }
      };

  private static final int PREBUILT_MATCHERS = 2;

  public static final ElementMatcher.Junction<TypeDescription> isClass =
      prepare(MatcherKind.CLASS, ElementMatchers.any(), false);

  public static final ElementMatcher.Junction<TypeDescription> isAbstract =
      prepare(MatcherKind.CLASS, ElementMatchers.isAbstract(), false);

  public static void reset() {
    if (matchers.size() > PREBUILT_MATCHERS) {
      noMatchCache.clear();
      memos.clear();
    }
  }

  public static <T> ElementMatcher.Junction<TypeDescription> prepare(
      MatcherKind kind, ElementMatcher.Junction<T> matcher, boolean inherited) {

    MemoizingMatcher memoizingMatcher =
        memoizingMatcherCache.computeIfAbsent(matcher, newMemoizingMatcher);

    int matcherId = memoizingMatcher.matcherId;
    if (matcherId < matchers.size()) {
      return memoizingMatcher;
    } else {
      matchers.add(matcher);
    }

    switch (kind) {
      case ANNOTATION:
        annotationMatcherIds.set(matcherId);
        break;
      case FIELD:
        fieldMatcherIds.set(matcherId);
        break;
      case METHOD:
        methodMatcherIds.set(matcherId);
        break;
      case CLASS:
        classMatcherIds.set(matcherId);
        break;
      case INTERFACE:
        interfaceMatcherIds.set(matcherId);
        break;
      case TYPE:
        classMatcherIds.set(matcherId);
        interfaceMatcherIds.set(matcherId);
        break;
    }

    if (inherited) {
      inheritedMatcherIds.set(matcherId);
    }

    return memoizingMatcher;
  }

  static final class MemoizingMatcher
      extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
    final int matcherId;

    public MemoizingMatcher(int matcherId) {
      this.matcherId = matcherId;
    }

    @Override
    protected boolean doMatch(TypeDescription target) {
      return memoize(target).get(matcherId);
    }
  }

  static BitSet memoize(TypeDescription target) {
    String targetName = target.getName();
    ClassLoader loader = TypePoolFacade.currentContext();
    if (noMatchCache.contains(targetName, loader)) {
      return NO_MATCH;
    }
    return doMemoize(targetName, target, loader, localMemos.get());
  }

  static BitSet memoize(
      TypeDefinition superTarget, ClassLoader loader, Map<String, BitSet> localMemos) {
    String superTargetName = superTarget.getTypeName();
    if (noMatchCache.contains(superTargetName, loader)) {
      return NO_MATCH;
    }
    return doMemoize(superTargetName, superTarget.asErasure(), loader, localMemos);
  }

  private static BitSet doMemoize(
      String targetName,
      TypeDescription target,
      ClassLoader loader,
      Map<String, BitSet> localMemos) {

    BitSet memo = localMemos.get(targetName);
    if (null != memo) {
      return memo;
    }

    TypeInfoCache.SharedTypeInfo<BitSet> existingMemo = memos.find(targetName);
    if (null != existingMemo && existingMemo.sameClassLoader(loader)) {
      return existingMemo.get();
    }

    localMemos.put(targetName, memo = new BitSet(matchers.size()));
    try {
      TypeDescription.Generic superTarget = target.getSuperClass();
      if (null != superTarget && !"java.lang.Object".equals(superTarget.getTypeName())) {
        inherit(memoize(superTarget, loader, localMemos), memo);
      }
      for (TypeDescription.Generic intf : target.getInterfaces()) {
        inherit(memoize(intf, loader, localMemos), memo);
      }
      for (AnnotationDescription ann : target.getDeclaredAnnotations()) {
        record(annotationMatcherIds, ann.getAnnotationType(), memo);
      }
      for (FieldDescription field : target.getDeclaredFields()) {
        record(fieldMatcherIds, field, memo);
      }
      for (MethodDescription method : target.getDeclaredMethods()) {
        record(methodMatcherIds, method, memo);
      }
      record(target.isInterface() ? interfaceMatcherIds : classMatcherIds, target, memo);
    } catch (Exception e) {
      if (log.isDebugEnabled()) {
        log.debug(
            "{} recording matches for target {}: {}",
            e.getClass().getSimpleName(),
            targetName,
            e.getMessage());
      }
    } finally {
      localMemos.remove(targetName);
    }

    if (memo.nextSetBit(PREBUILT_MATCHERS) > 0) {
      memos.share(targetName, loader, null, memo);
      return memo;
    } else {
      noMatchCache.add(targetName, loader);
      return NO_MATCH;
    }
  }

  private static void inherit(BitSet superMemo, BitSet memo) {
    int matcherId = superMemo.nextSetBit(0);
    while (matcherId >= 0) {
      if (inheritedMatcherIds.get(matcherId)) {
        memo.set(matcherId);
      }
      matcherId = superMemo.nextSetBit(matcherId + 1);
    }
  }

  @SuppressWarnings("unchecked")
  private static void record(BitSet matcherIds, Object target, BitSet memo) {
    int matcherId = matcherIds.nextSetBit(0);
    while (matcherId >= 0) {
      if (!memo.get(matcherId) && matchers.get(matcherId).matches(target)) {
        memo.set(matcherId);
      }
      matcherId = matcherIds.nextSetBit(matcherId + 1);
    }
  }
}
