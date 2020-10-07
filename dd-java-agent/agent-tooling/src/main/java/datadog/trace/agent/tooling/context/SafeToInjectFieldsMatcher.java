package datadog.trace.agent.tooling.context;

import datadog.trace.bootstrap.FieldBackedContextStoreAppliedMarker;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.security.ProtectionDomain;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

@Slf4j
final class SafeToInjectFieldsMatcher implements AgentBuilder.RawMatcher {

  public static AgentBuilder.RawMatcher of(String keyType, String valueType) {
    return new SafeToInjectFieldsMatcher(keyType, valueType);
  }

  private final String keyType;
  private final String valueType;
  private final ExcludeFilter.ExcludeType skipType;

  private SafeToInjectFieldsMatcher(String keyType, String valueType) {
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

    // First check if we should skip injecting the field based on the key type
    if (skipType != null && ExcludeFilter.exclude(skipType, typeDescription.getName())) {
      if (log.isDebugEnabled()) {
        log.debug(
            "Skipping context-store field for {}: {} -> {}",
            typeDescription.getName(),
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
    boolean result =
        classBeingRedefined == null
            || Arrays.asList(classBeingRedefined.getInterfaces())
                .contains(FieldBackedContextStoreAppliedMarker.class);
    if (log.isDebugEnabled()) {
      if (result) {
        // Only log success the first time we add it to the class
        if (classBeingRedefined == null) {
          log.debug(
              "Added context-store field to {}: {} -> {}",
              typeDescription.getName(),
              keyType,
              valueType);
        }
      } else {
        // This will log for every failed redefine
        log.debug(
            "Failed to add context-store field to {}: {} -> {}",
            typeDescription.getName(),
            keyType,
            valueType);
      }
    }
    return result;
  }
}
