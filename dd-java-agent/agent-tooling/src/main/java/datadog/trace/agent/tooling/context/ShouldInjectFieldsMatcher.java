package datadog.trace.agent.tooling.context;

import static datadog.trace.agent.tooling.context.ShouldInjectFieldsState.excludeInjectedField;
import static datadog.trace.agent.tooling.context.ShouldInjectFieldsState.findInjectionTarget;

import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.security.ProtectionDomain;
import java.util.Arrays;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ShouldInjectFieldsMatcher {
  private static final Logger log = LoggerFactory.getLogger(ShouldInjectFieldsMatcher.class);

  private final String keyType;
  private final String valueType;
  private final ExcludeFilter.ExcludeType skipType;

  ShouldInjectFieldsMatcher(String keyType, String valueType) {
    this.keyType = keyType;
    this.valueType = valueType;
    this.skipType = ExcludeFilter.ExcludeType.fromFieldType(keyType);
  }

  public final boolean matches(
      final TypeDescription typeDescription,
      final ClassLoader classLoader,
      final JavaModule module,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain) {
    String matchedType = typeDescription.getName();

    // First check if we should skip injecting the field based on the key type
    if (skipType != null && ExcludeFilter.exclude(skipType, matchedType)) {
      excludeInjectedField(matchedType, keyType, valueType);
      if (log.isDebugEnabled()) {
        log.debug(
            "Skipping context-store field - instrumentation.target.class={} instrumentation.target.classloader={} instrumentation.target.context={}->{}",
            matchedType,
            classLoader,
            keyType,
            valueType);
      }
      return false;
    }
    /*
     * The idea here is that we can add fields if class is just being loaded
     * (classBeingRedefined == null) and we have to add same fields again if class we added
     * fields before is being transformed again. Note: here we assume that Class#getInterfaces()
     * returns list of interfaces defined immediately on a given class, not inherited from its
     * parents. It looks like current JVM implementation does exactly this but javadoc is not
     * explicit about that.
     */
    boolean shouldInject =
        classBeingRedefined == null
            || Arrays.asList(classBeingRedefined.getInterfaces())
                .contains(FieldBackedContextAccessor.class);
    String injectionTarget = null;
    if (shouldInject) {
      // will always inject the key type if it's a class,
      // if this isn't the key class, we need to find the
      // last super class that implements the key type,
      // if it is an interface. This could be streamlined
      // slightly if we knew whether the key type were an
      // interface or a class, but can be figured out as
      // we go along
      if (!keyType.equals(matchedType)) {
        injectionTarget = findInjectionTarget(typeDescription, keyType);
        shouldInject &= matchedType.equals(injectionTarget);
      }
    }
    if (log.isDebugEnabled()) {
      if (shouldInject) {
        // Only log success the first time we add it to the class
        if (classBeingRedefined == null) {
          log.debug(
              "Added context-store field - instrumentation.target.class={} instrumentation.target.classloader={} instrumentation.target.context={}->{}",
              matchedType,
              classLoader,
              keyType,
              valueType);
        }
      } else if (null != injectionTarget) {
        log.debug(
            "Will not add context-store field, alternate target found {} - instrumentation.target.class={} instrumentation.target.classloader={} instrumentation.target.context={}->{}",
            injectionTarget,
            matchedType,
            classLoader,
            keyType,
            valueType);
      } else {
        // must be a redefine of a class that we weren't able to field-inject on startup
        // - make sure we'd have field-injected (if we'd had the chance) before tracking
        if (keyType.equals(matchedType)
            || matchedType.equals(findInjectionTarget(typeDescription, keyType))) {

          excludeInjectedField(matchedType, keyType, valueType);

          // Only log failed redefines where we would have injected this class
          log.debug(
              "Failed to add context-store field - instrumentation.target.class={} instrumentation.target.classloader={} instrumentation.target.context={}->{}",
              matchedType,
              classLoader,
              keyType,
              valueType);
        }
      }
    }
    return shouldInject;
  }
}
