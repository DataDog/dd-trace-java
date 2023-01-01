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

public final class InjectContextFieldMatcher implements AgentBuilder.RawMatcher {
  private static final Logger log = LoggerFactory.getLogger(InjectContextFieldMatcher.class);

  private final String keyType;
  private final String valueType;

  private final ElementMatcher<TypeDescription> shouldInjectContextField;
  private final ElementMatcher<ClassLoader> activator;

  public InjectContextFieldMatcher(
      String keyType, String valueType, ElementMatcher<ClassLoader> activator) {
    this.keyType = keyType;
    this.valueType = valueType;

    this.shouldInjectContextField = declaresContextField(keyType, valueType);
    this.activator = activator;
  }

  @Override
  public boolean matches(
      TypeDescription target,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain pd) {

    if (!activator.matches(classLoader)) {
      return false; // skip detailed check if key isn't visible
    }

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
      return shouldInjectContextField.matches(target);
    }

    if (log.isDebugEnabled()) {
      // must be a re-define of a class that we weren't able to field-inject on startup
      // - make sure we'd have field-injected (if we'd had the chance) before reporting
      if (shouldInjectContextField.matches(target)) {
        log.debug(
            "Failed to add context-store field - instrumentation.target.class={} instrumentation.target.context={}->{}",
            target.getName(),
            keyType,
            valueType);
      }
    }

    return false;
  }
}
