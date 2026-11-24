package datadog.trace.agent.tooling.bytebuddy.memoize;

import static java.util.Arrays.asList;
import static net.bytebuddy.matcher.ElementMatchers.not;

import datadog.instrument.utils.ClassNameFilter;
import datadog.trace.agent.tooling.InstrumenterMetrics;
import datadog.trace.agent.tooling.bytebuddy.TypeInfoCache;
import datadog.trace.agent.tooling.bytebuddy.TypeInfoCache.SharedTypeInfo;
import datadog.trace.agent.tooling.bytebuddy.matcher.ConstantPoolNeedles;
import datadog.trace.agent.tooling.bytebuddy.matcher.ConstantPoolSieve;
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import datadog.trace.agent.tooling.bytebuddy.outline.WithLocation;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressForbidden
@SuppressWarnings("rawtypes")
public final class Memoizer {
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

  // records the kind of matcher and whether matches should be inherited
  private static final BitSet annotationMatcherIds = new BitSet(SIZE_HINT);
  private static final BitSet fieldMatcherIds = new BitSet(SIZE_HINT);
  private static final BitSet methodMatcherIds = new BitSet(SIZE_HINT);
  private static final BitSet classMatcherIds = new BitSet(SIZE_HINT);
  private static final BitSet interfaceMatcherIds = new BitSet(SIZE_HINT);
  private static final BitSet inheritedMatcherIds = new BitSet(SIZE_HINT);

  // matchers that are ready for memoization
  static final List<ElementMatcher> matchers = new ArrayList<>();

  // small cache to de-duplicate memoization requests
  private static final DDCache<ElementMatcher, MemoizingMatcher> memoizingMatcherCache =
      DDCaches.newFixedSizeIdentityCache(8);

  private static final boolean namesAreUnique = InstrumenterConfig.get().isResolverNamesAreUnique();

  // compact filter recording uninteresting types
  private static final ClassNameFilter noMatchFilter = NoMatchFilter.build();

  // caches positive memoized matches
  private static final TypeInfoCache<BitSet> memos =
      new TypeInfoCache<>(InstrumenterConfig.get().getResolverMemoPoolSize(), namesAreUnique);

  // local memoized results, used to detect circular references
  static final ThreadLocal<Map<String, BitSet>> localMemosHolder =
      ThreadLocal.withInitial(HashMap::new);

  private static final int INTERNAL_MATCHERS = 3; // isClass, isConcrete, isPartial

  // experimental: skips member matchers whose names aren't in the type's constant pool
  private static volatile MemberSieve memberSieve;

  private static final boolean verifyMemberSieve =
      InstrumenterConfig.get().isResolverDeclaredMethodSieveVerify();

  // memoize whether the type is a class
  static final MemoizingMatcher isClass = prepare(MatcherKind.CLASS, ElementMatchers.any(), true);

  // memoize whether the type is a concrete class, i.e. no abstract methods
  static final MemoizingMatcher isConcrete =
      prepare(MatcherKind.CLASS, not(ElementMatchers.isAbstract()), false);

  // memoize whether this is based on partial information due to missing types
  static final MemoizingMatcher isPartial = prepare(MatcherKind.TYPE, ElementMatchers.none(), true);

  public static void resetState() {
    // no need to reset the state if we haven't added any external matchers
    if (matchers.size() > INTERNAL_MATCHERS) {
      noMatchFilter.clear();
      Memoizer.clear();
    }
  }

  public static void clear() {
    memos.clear();
  }

  static MemoizingMatcher withMatcherId(ElementMatcher matcher) {
    return new MemoizingMatcher(matchers.size());
  }

  /** Prepares a matcher for memoization. */
  static <T> MemoizingMatcher prepare(
      MatcherKind kind, ElementMatcher<T> matcher, boolean inherited) {

    MemoizingMatcher memoizingMatcher =
        memoizingMatcherCache.computeIfAbsent(matcher, Memoizer::withMatcherId);

    int matcherId = memoizingMatcher.matcherId;
    if (matcherId < matchers.size()) {
      return memoizingMatcher; // de-duplicated matcher
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

  /** Matcher wrapper that triggers memoization on request. */
  static final class MemoizingMatcher
      extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
    final int matcherId;

    MemoizingMatcher(int matcherId) {
      this.matcherId = matcherId;
    }

    @Override
    protected boolean doMatch(TypeDescription target) {
      String targetName = target.getName();
      if (noMatchFilter.contains(targetName)
          || "java.lang.Object".equals(targetName)
          || target.isPrimitive()) {
        return false;
      } else {
        return doMemoize(target, localMemosHolder.get()).get(matcherId);
      }
    }
  }

  static BitSet memoizeHierarchy(TypeDescription type, Map<String, BitSet> localMemos) {
    if (noMatchFilter.contains(type.getName())) {
      return NO_MATCH;
    } else {
      return doMemoize(type, localMemos);
    }
  }

  static BitSet doMemoize(TypeDescription type, Map<String, BitSet> localMemos) {

    String name = type.getName();
    BitSet memo = localMemos.get(name);
    if (null != memo) {
      return memo; // short-circuit circular references
    }

    long fromTick = InstrumenterMetrics.tick();
    SharedTypeInfo<BitSet> sharedMemo = memos.find(name);
    if (null != sharedMemo) {
      if (namesAreUnique || name.startsWith("java.") || sameOrigin(type, sharedMemo)) {
        InstrumenterMetrics.reuseTypeMemo(fromTick);
        return sharedMemo.get();
      }
    }

    localMemos.put(name, memo = new BitSet(matchers.size()));
    boolean wasFullParsing = TypePoolFacade.disableFullDescriptions(); // only need outlines here
    try {
      TypeDescription.Generic superType = type.getSuperClass();
      long superTick = InstrumenterMetrics.tick();
      if (null != superType && !"java.lang.Object".equals(superType.getTypeName())) {
        inherit(memoizeHierarchy(superType.asErasure(), localMemos), memo);
      }
      for (TypeDescription.Generic intf : type.getInterfaces()) {
        inherit(memoizeHierarchy(intf.asErasure(), localMemos), memo);
      }
      fromTick += (InstrumenterMetrics.tick() - superTick); // adjust to exclude super-type ticks
      MemberSieve sieve = memberSieve;
      BitSet present = null != sieve ? sieve.scan(type) : null;
      // only ask for members if a matcher might apply, so unneeded members are never parsed
      BitSet annotationIds = candidates(sieve, annotationMatcherIds, present);
      if (!annotationIds.isEmpty()) {
        for (AnnotationDescription ann : type.getDeclaredAnnotations()) {
          record(annotationIds, ann.getAnnotationType(), memo);
        }
      }
      BitSet fieldIds = candidates(sieve, fieldMatcherIds, present);
      if (!fieldIds.isEmpty()) {
        for (FieldDescription field : type.getDeclaredFields()) {
          record(fieldIds, field, memo);
        }
      }
      BitSet methodIds = candidates(sieve, methodMatcherIds, present);
      if (!methodIds.isEmpty()) {
        for (MethodDescription method : type.getDeclaredMethods()) {
          record(methodIds, method, memo);
        }
      }
      if (verifyMemberSieve && null != present) {
        verifySkipped(type, annotationIds, fieldIds, methodIds);
      }
      record(type.isInterface() ? interfaceMatcherIds : classMatcherIds, type, memo);
    } catch (Throwable e) {
      // we're missing some type information so record the result as partial
      memo.set(isPartial.matcherId);
      if (log.isDebugEnabled()) {
        log.debug(
            "{} recording matches for type {}: {}",
            e.getClass().getSimpleName(),
            name,
            e.getMessage());
      }
    } finally {
      localMemos.remove(name);
      if (wasFullParsing) {
        TypePoolFacade.enableFullDescriptions();
      }
    }

    InstrumenterMetrics.buildTypeMemo(fromTick);

    // update no-match filter if there's no interesting matches and result is complete
    if (memo.nextSetBit(INTERNAL_MATCHERS) < 0 && !memo.get(isPartial.matcherId)) {
      noMatchFilter.add(name);
      return NO_MATCH;
    }

    // otherwise share result for this location (other locations may have different results)
    if (namesAreUnique || name.startsWith("java.") || !(type instanceof WithLocation)) {
      memos.share(name, 0, null, memo);
    } else {
      WithLocation origin = (WithLocation) type;
      memos.share(name, origin.getClassLoaderId(), origin.getClassFile(), memo);
    }

    return memo;
  }

  /**
   * Builds the member sieve over the memoized annotation, field, and method matchers registered so
   * far. Matchers registered later aren't sieved, i.e. they always run.
   */
  public static void prepareMemberSieve() {
    int matcherCount = matchers.size();
    String[][] names = new String[matcherCount][];
    Set<String> allNames = new HashSet<>();
    collectNames(annotationMatcherIds, true, names, allNames);
    collectNames(fieldMatcherIds, false, names, allNames);
    collectNames(methodMatcherIds, false, names, allNames);
    memberSieve = new MemberSieve(new ConstantPoolSieve(allNames), names);
    if (log.isDebugEnabled()) {
      log.debug("Member sieve covers {} names", allNames.size());
      logUnsieved("annotation", annotationMatcherIds, names);
      logUnsieved("field", fieldMatcherIds, names);
      logUnsieved("method", methodMatcherIds, names);
    }
  }

  private static void logUnsieved(String kind, BitSet matcherIds, String[][] names) {
    int total = 0;
    List<String> unsieved = new ArrayList<>();
    for (int id = matcherIds.nextSetBit(0); id >= 0; id = matcherIds.nextSetBit(id + 1)) {
      total++;
      if (null == names[id]) {
        unsieved.add(String.valueOf(matchers.get(id)));
      }
    }
    log.debug(
        "Member sieve: {} of {} {} matchers not sieved: {}",
        unsieved.size(),
        total,
        kind,
        unsieved);
  }

  private static void collectNames(
      BitSet matcherIds, boolean annotationTypes, String[][] names, Set<String> allNames) {
    for (int id = matcherIds.nextSetBit(0); id >= 0; id = matcherIds.nextSetBit(id + 1)) {
      Set<String> lowered =
          annotationTypes
              ? ConstantPoolNeedles.forTypeName(matchers.get(id))
              : ConstantPoolNeedles.forMember(matchers.get(id));
      if (null != lowered) {
        String[] needles = new String[lowered.size()];
        int i = 0;
        for (String name : lowered) {
          // annotation types appear in the constant pool as descriptors, e.g. Ljavax/ws/rs/Path;
          needles[i++] = annotationTypes ? 'L' + name.replace('.', '/') + ';' : name;
        }
        names[id] = needles;
        allNames.addAll(asList(needles));
      }
    }
  }

  /** Runs the matchers the sieve skipped against the real members; logs any that match. */
  private static void verifySkipped(
      TypeDescription type, BitSet annotationIds, BitSet fieldIds, BitSet methodIds) {
    BitSet missed = new BitSet();
    BitSet skipped = (BitSet) annotationMatcherIds.clone();
    skipped.andNot(annotationIds);
    if (!skipped.isEmpty()) {
      for (AnnotationDescription ann : type.getDeclaredAnnotations()) {
        record(skipped, ann.getAnnotationType(), missed);
      }
    }
    skipped = (BitSet) fieldMatcherIds.clone();
    skipped.andNot(fieldIds);
    if (!skipped.isEmpty()) {
      for (FieldDescription field : type.getDeclaredFields()) {
        record(skipped, field, missed);
      }
    }
    skipped = (BitSet) methodMatcherIds.clone();
    skipped.andNot(methodIds);
    if (!skipped.isEmpty()) {
      for (MethodDescription method : type.getDeclaredMethods()) {
        record(skipped, method, missed);
      }
    }
    for (int id = missed.nextSetBit(0); id >= 0; id = missed.nextSetBit(id + 1)) {
      log.warn("Member sieve skipped matcher {} for {} but it matches", matchers.get(id), type);
    }
  }

  /** Narrows the given matcher ids to those whose names may be present; all if not sieved. */
  private static BitSet candidates(MemberSieve sieve, BitSet matcherIds, BitSet present) {
    if (null == present) {
      return matcherIds;
    }
    BitSet candidates = new BitSet();
    for (int id = matcherIds.nextSetBit(0); id >= 0; id = matcherIds.nextSetBit(id + 1)) {
      if (sieve.mayMatch(id, present)) {
        candidates.set(id);
      }
    }
    return candidates;
  }

  /** Sieve over the names that memoized member matchers require. */
  static final class MemberSieve {
    private final ConstantPoolSieve sieve;
    private final int[][] nameIds; // indexed by matcher id; null means the matcher isn't sieved

    MemberSieve(ConstantPoolSieve sieve, String[][] names) {
      this.sieve = sieve;
      this.nameIds = new int[names.length][];
      for (int id = 0; id < names.length; id++) {
        if (null != names[id]) {
          int[] ids = new int[names[id].length];
          for (int i = 0; i < ids.length; i++) {
            ids[i] = sieve.id(names[id][i]);
          }
          nameIds[id] = ids;
        }
      }
    }

    /** Names present in the type's unparsed class file, or null if it can't be sieved. */
    BitSet scan(TypeDescription type) {
      byte[] bytecode = TypePoolFacade.unparsedOutlineBytecode(type);
      if (null == bytecode) {
        return null; // members already parsed, or not an outline
      }
      BitSet present = new BitSet(sieve.size());
      return sieve.scan(bytecode, present) ? present : null;
    }

    boolean mayMatch(int matcherId, BitSet present) {
      if (matcherId >= nameIds.length || null == nameIds[matcherId]) {
        return true;
      }
      for (int id : nameIds[matcherId]) {
        if (id < 0 || present.get(id)) {
          return true;
        }
      }
      return false;
    }
  }

  /** Any type not recorded as a definite "no-match" is a potential match. */
  static boolean potentialMatch(String name) {
    return !noMatchFilter.contains(name);
  }

  private static boolean sameOrigin(TypeDescription type, SharedTypeInfo<BitSet> sharedMemo) {
    return !(type instanceof WithLocation)
        || sharedMemo.sameClassLoader(((WithLocation) type).getClassLoaderId())
        || sharedMemo.sameClassFile(((WithLocation) type).getClassFile());
  }

  /** Inherit positive matches from a super-class or interface. */
  private static void inherit(BitSet superMemo, BitSet memo) {
    int matcherId = superMemo.nextSetBit(0);
    while (matcherId >= 0) {
      if (inheritedMatcherIds.get(matcherId)) {
        memo.set(matcherId);
      }
      matcherId = superMemo.nextSetBit(matcherId + 1);
    }
  }

  /** Run a series of memoized matchers and record positive matches. */
  @SuppressWarnings("unchecked")
  private static void record(BitSet matcherIds, Object type, BitSet memo) {
    int matcherId = matcherIds.nextSetBit(0);
    while (matcherId >= 0) {
      // can skip match if we've already inherited a positive result
      if (!memo.get(matcherId) && matchers.get(matcherId).matches(type)) {
        memo.set(matcherId);
      }
      matcherId = matcherIds.nextSetBit(matcherId + 1);
    }
  }
}
