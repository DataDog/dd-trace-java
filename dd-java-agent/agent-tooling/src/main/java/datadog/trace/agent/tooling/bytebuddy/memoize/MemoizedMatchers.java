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

public final class MemoizedMatchers implements HierarchyMatchers.Supplier {
  public static void registerAsSupplier() {
    HierarchyMatchers.registerIfAbsent(new MemoizedMatchers());
    Memoizer.reset();
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> declaresAnnotation(
      ElementMatcher.Junction<? super NamedElement> matcher) {
    return Memoizer.prepare(ANNOTATION, matcher, false);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> declaresField(
      ElementMatcher.Junction<? super FieldDescription> matcher) {
    return Memoizer.prepare(FIELD, matcher, false);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> declaresMethod(
      ElementMatcher.Junction<? super MethodDescription> matcher) {
    return Memoizer.prepare(METHOD, matcher, false);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> abstractClass() {
    return Memoizer.isAbstract;
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> extendsClass(
      ElementMatcher.Junction<? super TypeDescription> matcher) {
    return Memoizer.prepare(CLASS, matcher, true);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> implementsInterface(
      ElementMatcher.Junction<? super TypeDescription> matcher) {
    return Memoizer.isClass.and(Memoizer.prepare(INTERFACE, matcher, true));
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> hasInterface(
      ElementMatcher.Junction<? super TypeDescription> matcher) {
    return Memoizer.prepare(INTERFACE, matcher, true);
  }

  @Override
  public ElementMatcher.Junction<TypeDescription> hasSuperType(
      ElementMatcher.Junction<? super TypeDescription> matcher) {
    return Memoizer.isClass.and(Memoizer.prepare(TYPE, matcher, true));
  }

  @Override
  public ElementMatcher.Junction<MethodDescription> hasSuperMethod(
      ElementMatcher.Junction<? super MethodDescription> matcher) {
    return new HasSuperMethod(Memoizer.prepare(METHOD, matcher, true), matcher);
  }

  private static final Map<String, HasContextField> contextFields = new HashMap<>();

  @Override
  public ElementMatcher.Junction<TypeDescription> declaresContextField(
      String keyType, String contextType) {

    ExcludeFilter.ExcludeType excludeType = ExcludeFilter.ExcludeType.fromFieldType(keyType);

    HasContextField contextField = contextFields.get(keyType);
    if (null == contextField) {
      ExpectContextField expectMatcher = new ExpectContextField(hasSuperType(named(keyType)));
      if (null != excludeType) {
        ElementMatcher<TypeDescription> excludeMatcher =
            Memoizer.prepare(CLASS, expectMatcher.and(new ExcludeContextField(excludeType)), true);
        contextField = new HasContextField(expectMatcher, excludeMatcher);
      } else {
        contextField = new HasContextField(expectMatcher);
      }
      contextFields.put(keyType, contextField);
    }

    if (null != excludeType) {
      contextField.maybeExclude(getContextStoreId(keyType, contextType));
    }

    return contextField;
  }

  public static boolean hasSuperStores(TypeDescription target, BitSet excludedStoreIds) {
    boolean hasSuperStores = false;
    for (HasContextField contextField : contextFields.values()) {
      if (contextField.hasSuperStore(target, excludedStoreIds)) {
        hasSuperStores = true;
      }
    }
    return hasSuperStores;
  }
}
