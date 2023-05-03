package datadog.trace.agent.tooling.context;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.context.ShouldInjectFieldsState.excludeInjectedField;
import static datadog.trace.agent.tooling.context.ShouldInjectFieldsState.findInjectionTarget;

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ShouldInjectFieldsMatcher
    extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
  private static final Logger log = LoggerFactory.getLogger(ShouldInjectFieldsMatcher.class);

  private final String keyType;
  private final String valueType;
  private final ElementMatcher<TypeDescription> subclassOfKey;
  private final ExcludeFilter.ExcludeType skipType;

  public ShouldInjectFieldsMatcher(String keyType, String valueType) {
    this.keyType = keyType;
    this.valueType = valueType;
    this.subclassOfKey = hasSuperType(named(keyType));
    this.skipType = ExcludeFilter.ExcludeType.fromFieldType(keyType);
  }

  @Override
  protected boolean doMatch(TypeDescription typeDescription) {
    if (!subclassOfKey.matches(typeDescription)) {
      return false; // ignore types that are completely unrelated to the key
    }

    String matchedType = typeDescription.getName();

    // First check if we should skip injecting the field based on the key type
    if (skipType != null && ExcludeFilter.exclude(skipType, matchedType)) {
      excludeInjectedField(matchedType, keyType, valueType);
      if (log.isDebugEnabled()) {
        log.debug(
            "Skipping context-store field - instrumentation.target.class={} instrumentation.target.context={}->{}",
            matchedType,
            keyType,
            valueType);
      }
      return false;
    }
    String injectionTarget = null;
    // we will always inject the key type if it's a class - if this isn't the key class we need to
    // find the last super class that implements the key type, if it is an interface. This could be
    // streamlined slightly if we knew whether the key type were an interface or a class, but can be
    // figured out as we go along
    boolean shouldInject;
    if (keyType.equals(matchedType)) {
      shouldInject = true;
    } else {
      injectionTarget = findInjectionTarget(typeDescription, keyType);
      shouldInject = matchedType.equals(injectionTarget);
    }
    if (log.isDebugEnabled()) {
      if (!shouldInject && null != injectionTarget) {
        log.debug(
            "Will not add context-store field, alternate target found {} - instrumentation.target.class={} instrumentation.target.context={}->{}",
            injectionTarget,
            matchedType,
            keyType,
            valueType);
      }
    }
    return shouldInject;
  }
}
