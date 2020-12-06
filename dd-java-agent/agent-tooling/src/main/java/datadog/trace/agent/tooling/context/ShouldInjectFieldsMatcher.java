package datadog.trace.agent.tooling.context;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.FieldBackedContextStoreAppliedMarker;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

@Slf4j
final class ShouldInjectFieldsMatcher implements AgentBuilder.RawMatcher {

  // this map will contain as many entries as there are unique
  // context store keys, so can't get very big
  private static final ConcurrentHashMap<String, Boolean> KEY_TYPE_IS_CLASS =
      new ConcurrentHashMap<>();

  private static final Class<?> FIELD_INJECTED_MARKER =
      Config.get().isLegacyContextFieldInjection()
          ? FieldBackedContextStoreAppliedMarker.class
          : null /* new field-injection marker */;

  public static AgentBuilder.RawMatcher of(String keyType, String valueType) {
    return new ShouldInjectFieldsMatcher(keyType, valueType);
  }

  private final String keyType;
  private final String valueType;
  private final ExcludeFilter.ExcludeType skipType;

  private ShouldInjectFieldsMatcher(String keyType, String valueType) {
    this.keyType = keyType;
    this.valueType = valueType;
    this.skipType = ExcludeFilter.ExcludeType.fromFieldType(keyType);
  }

  @Override
  public boolean matches(
      final TypeDescription typeDescription,
      final ClassLoader classLoader,
      final JavaModule module,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain) {
    String matchedType = typeDescription.getName();

    // First check if we should skip injecting the field based on the key type
    if (skipType != null && ExcludeFilter.exclude(skipType, matchedType)) {
      if (log.isDebugEnabled()) {
        log.debug("Skipping context-store field for {}: {} -> {}", matchedType, keyType, valueType);
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
            || Arrays.asList(classBeingRedefined.getInterfaces()).contains(FIELD_INJECTED_MARKER);
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
        injectionTarget = getInjectionTarget(typeDescription);
        shouldInject &= matchedType.equals(injectionTarget);
      }
    }
    if (log.isDebugEnabled()) {
      if (shouldInject) {
        // Only log success the first time we add it to the class
        if (classBeingRedefined == null) {
          log.debug("Added context-store field to {}: {} -> {}", matchedType, keyType, valueType);
        }
      } else if (null != injectionTarget) {
        log.debug(
            "Will not add context-store field to {}: {} -> {}, because it will be added to {}",
            matchedType,
            keyType,
            valueType,
            injectionTarget);
      } else {
        if (keyType.equals(matchedType)
            || matchedType.equals(getInjectionTarget(typeDescription))) {
          // Only log failed redefines where we would have injected this class
          log.debug(
              "Failed to add context-store field to {}: {} -> {}", matchedType, keyType, valueType);
        }
      }
    }
    return shouldInject;
  }

  private String getInjectionTarget(TypeDescription typeDescription) {
    // precondition: typeDescription must be a sub type of the key class
    // verifying this isn't free so the caller (in the same package) is trusted

    // The flag takes 3 values:
    // true: the key type is a class, so should be the injection target
    // false: the key type is an interface, so we need to find the class
    // closest to java.lang.Object which implements the key type
    // null: we don't know yet because we haven't seen the key type before
    Boolean keyTypeIsClass = KEY_TYPE_IS_CLASS.get(keyType);
    if (null != keyTypeIsClass && keyTypeIsClass) {
      // if we already know the key type is a class,
      // we must inject into that class.
      return keyType;
    }
    // then we don't know it's a class so need to
    // follow the type's ancestry to find out
    TypeDefinition superClass = typeDescription.getSuperClass();
    String implementingClass = typeDescription.getName();
    Map<String, Boolean> visitedInterfaces = new HashMap<>();
    while (null != superClass) {
      String superClassName = superClass.asErasure().getTypeName();
      if (null == keyTypeIsClass && keyType.equals(superClassName)) {
        // short circuit the search with this key type next time
        KEY_TYPE_IS_CLASS.put(keyType, true);
        return keyType;
      }
      if (hasKeyInterface(superClass, visitedInterfaces)) {
        // then the key type must be an interface
        if (null == keyTypeIsClass) {
          KEY_TYPE_IS_CLASS.put(keyType, false);
          keyTypeIsClass = false;
        }
        implementingClass = superClassName;
      }
      superClass = superClass.getSuperClass();
    }
    return implementingClass;
  }

  private boolean hasKeyInterface(
      final TypeDefinition typeDefinition, final Map<String, Boolean> visitedInterfaces) {
    for (TypeDefinition iface : typeDefinition.getInterfaces()) {
      String interfaceName = iface.asErasure().getTypeName();
      if (keyType.equals(interfaceName)) {
        return true;
      }
      Boolean foundKeyInterface = visitedInterfaces.get(interfaceName);
      if (Boolean.TRUE.equals(foundKeyInterface)) {
        return true; // already know this will lead to the key
      }
      if (null == foundKeyInterface) {
        // avoid cycle issues by assuming we won't find the key
        visitedInterfaces.put(interfaceName, false);
        if (hasKeyInterface(iface, visitedInterfaces)) {
          visitedInterfaces.put(interfaceName, true); // update assumption
          return true;
        }
      }
    }
    return false;
  }
}
