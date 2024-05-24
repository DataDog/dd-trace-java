package datadog.trace.agent.tooling.bytebuddy.memoize;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.memoize.Memoizer.MatcherKind.ANNOTATION;
import static datadog.trace.agent.tooling.bytebuddy.memoize.Memoizer.MatcherKind.CLASS;
import static datadog.trace.agent.tooling.bytebuddy.memoize.Memoizer.MatcherKind.FIELD;
import static datadog.trace.agent.tooling.bytebuddy.memoize.Memoizer.MatcherKind.INTERFACE;
import static datadog.trace.agent.tooling.bytebuddy.memoize.Memoizer.MatcherKind.METHOD;
import static datadog.trace.agent.tooling.bytebuddy.memoize.Memoizer.MatcherKind.TYPE;
import static datadog.trace.bootstrap.FieldBackedContextStores.getContextStoreId;

import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Supplies memoized matchers. */
public final class MemoizedMatchers implements HierarchyMatchers.Supplier {
  public static void registerAsSupplier() {
    PreloadHierarchy.observeClassDefinitions();
    HierarchyMatchers.registerIfAbsent(new MemoizedMatchers());
    Memoizer.resetState();
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> declaresAnnotation(
      ElementMatcher<? super NamedElement> matcher) {
    return Memoizer.prepare(ANNOTATION, matcher, false);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> declaresField(
      ElementMatcher<? super FieldDescription> matcher) {
    return Memoizer.prepare(FIELD, matcher, false);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> declaresMethod(
      ElementMatcher<? super MethodDescription> matcher) {
    return Memoizer.prepare(METHOD, matcher, false);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> concreteClass() {
    return Memoizer.isConcrete;
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> extendsClass(
      ElementMatcher<? super TypeDescription> matcher) {
    return Memoizer.prepare(CLASS, matcher, true);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> implementsInterface(
      ElementMatcher<? super TypeDescription> matcher) {
    return Memoizer.isClass.and(Memoizer.prepare(INTERFACE, matcher, true));
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> hasInterface(
      ElementMatcher<? super TypeDescription> matcher) {
    return Memoizer.prepare(INTERFACE, matcher, true);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> hasSuperType(
      ElementMatcher<? super TypeDescription> matcher) {
    return Memoizer.isClass.and(Memoizer.prepare(TYPE, matcher, true));
  }

  @Override
  public ElementMatcher.Junction<MethodDescription> hasSuperMethod(
      ElementMatcher<? super MethodDescription> matcher) {
    return new HasSuperMethod(Memoizer.prepare(METHOD, matcher, true), matcher);
  }

  /** Keeps track of which context-field matchers we've supplied so far. */
  private static final Map<String, HasContextField> contextFields = new HashMap<>();

  @Override
  public ElementMatcher.Junction<TypeDescription> declaresContextField(
      String keyType, String contextType) {

    // is there a chance a type might match, but be excluded (skipped) from field-injection?
    ExcludeFilter.ExcludeType excludeType = ExcludeFilter.ExcludeType.fromFieldType(keyType);

    HasContextField contextField = contextFields.get(keyType);
    if (null == contextField) {
      ElementMatcher<TypeDescription> storeMatcher = hasSuperType(named(keyType));
      if (null != excludeType) {
        ElementMatcher<TypeDescription> skipMatcher =
            Memoizer.prepare(CLASS, new HasContextField.Skip(excludeType), true);
        contextField = new HasContextField(storeMatcher, skipMatcher);
      } else {
        contextField = new HasContextField(storeMatcher);
      }
      contextFields.put(keyType, contextField);
    }

    if (null != excludeType) {
      // record that this store may be skipped from field-injection
      contextField.maybeSkip(getContextStoreId(keyType, contextType));
    }

    return contextField;
  }

  /**
   * Returns {@code true} if ancestors of the given type have any context-stores.
   *
   * <p>Stores which were skipped from field-injection in the super-class hierarchy are recorded in
   * {@code weakStoreIds} so the appropriate weak-map delegation can be applied when injecting code
   * to handle context-store requests.
   */
  public static boolean hasSuperStores(TypeDescription target, BitSet weakStoreIds) {
    boolean hasSuperStores = false;
    for (HasContextField contextField : contextFields.values()) {
      if (contextField.hasSuperStore(target, weakStoreIds)) {
        hasSuperStores = true;
      }
    }
    return hasSuperStores;
  }
}
