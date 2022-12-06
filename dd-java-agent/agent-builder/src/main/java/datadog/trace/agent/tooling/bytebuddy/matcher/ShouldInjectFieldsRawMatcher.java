package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresContextField;

import datadog.trace.bootstrap.FieldBackedContextAccessor;
import java.security.ProtectionDomain;
import java.util.Arrays;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ShouldInjectFieldsRawMatcher implements AgentBuilder.RawMatcher {
  private static final Logger log = LoggerFactory.getLogger(ShouldInjectFieldsRawMatcher.class);

  private final String keyType;
  private final String valueType;

  private final ElementMatcher.Junction<TypeDescription> shouldInjectContextField;

  public ShouldInjectFieldsRawMatcher(String keyType, String valueType) {
    this.keyType = keyType;
    this.valueType = valueType;

    shouldInjectContextField = declaresContextField(keyType, valueType);
  }

  @Override
  public boolean matches(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain) {

    /*
     * The idea here is that we can add fields if class is just being loaded
     * (classBeingRedefined == null) and we have to add same fields again if
     * the class we added fields before is being transformed again.
     *
     * Note: here we assume that Class#getInterfaces() returns list of interfaces
     * defined immediately on a given class, not inherited from its parents. It
     * looks like current JVM implementation does exactly this but javadoc is not
     * explicit about that.
     */
    boolean canInjectContextField =
        classBeingRedefined == null
            || Arrays.asList(classBeingRedefined.getInterfaces())
                .contains(FieldBackedContextAccessor.class);

    if (canInjectContextField) {
      return shouldInjectContextField.matches(typeDescription);
    }

    if (log.isDebugEnabled()) {
      // must be a redefine of a class that we weren't able to field-inject on startup
      // - make sure we'd have field-injected (if we'd had the chance) before tracking
      if (shouldInjectContextField.matches(typeDescription)) {
        log.debug(
            "Failed to add context-store field - instrumentation.target.class={} instrumentation.target.context={}->{}",
            typeDescription.getName(),
            keyType,
            valueType);
      }
    }

    return false;
  }
}
