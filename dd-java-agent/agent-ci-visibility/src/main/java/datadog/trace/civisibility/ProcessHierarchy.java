package datadog.trace.civisibility;

import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_HOST;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_SIGNAL_SERVER_PORT;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.util.ConfigStrings.propertyNameToSystemPropertyName;

import datadog.environment.SystemProperties;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import java.net.InetSocketAddress;
import java.util.Map;
import javax.annotation.Nullable;

public class ProcessHierarchy {

  private static final class SystemPropertiesPropagationGetter
      implements AgentPropagation.ContextVisitor<Map<String, String>> {
    static final AgentPropagation.ContextVisitor<Map<String, String>> INSTANCE =
        new SystemPropertiesPropagationGetter();

    private SystemPropertiesPropagationGetter() {}

    @Override
    public void forEachKey(Map<String, String> carrier, AgentPropagation.KeyClassifier classifier) {
      for (Map.Entry<String, String> property : carrier.entrySet()) {
        if (!classifier.accept(property.getKey(), property.getValue())) {
          return;
        }
      }
    }
  }

  @Nullable public final AgentSpanContext.Extracted parentProcessModuleContext;

  ProcessHierarchy() {
    parentProcessModuleContext =
        extractContextAndGetSpanContext(
            SystemProperties.asStringMap(), SystemPropertiesPropagationGetter.INSTANCE);
  }

  /**
   * Module span context is propagated from the parent process if it runs with the tracer attached.
   * If module span context is note there, either we are in the build system, or we are in the tests
   * JVM and the build system is not instrumented.
   */
  public boolean isChild() {
    return parentProcessModuleContext != null;
  }

  /**
   * Determines if current process runs in "headless mode", i.e. has no instrumented parent and is
   * not one of the supported build system processes.
   */
  public boolean isHeadless() {
    return !isChild() && !isParent() && !isWrapper();
  }

  private boolean isParent() {
    return isMavenParent() || isGradleDaemon();
  }

  /**
   * Determines if current process is a wrapper that starts the build system. In other words a
   * process that is not a build system, and not a JVM that runs tests.
   */
  private boolean isWrapper() {
    // Maven Wrapper runs in the same JVM as Maven itself,
    // so it is not included here
    return isGradleLauncher();
  }

  private boolean isMavenParent() {
    return SystemProperties.get("maven.home") != null
            && SystemProperties.get("classworlds.conf") != null
        // when using Maven Wrapper
        || ClassLoader.getSystemClassLoader()
                .getResource("org/apache/maven/wrapper/WrapperExecutor.class")
            != null;
  }

  private boolean isGradleDaemon() {
    return ClassLoader.getSystemClassLoader()
                .getResource("org/gradle/launcher/daemon/bootstrap/GradleDaemon.class")
            != null
        // double-check this is not a Gradle Worker
        && SystemProperties.get("org.gradle.internal.worker.tmpdir") == null;
  }

  private boolean isGradleLauncher() {
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    return contextClassLoader.getResource("org/gradle/launcher/Main.class") != null
        || contextClassLoader.getResource("org/gradle/launcher/GradleMain.class") != null;
  }

  @Nullable
  public InetSocketAddress getSignalServerAddress() {
    // System properties are used rather than Config,
    // because system variables can be set after config was initialized
    String host =
        SystemProperties.get(propertyNameToSystemPropertyName(CIVISIBILITY_SIGNAL_SERVER_HOST));
    String port =
        SystemProperties.get(propertyNameToSystemPropertyName(CIVISIBILITY_SIGNAL_SERVER_PORT));
    if (host != null && port != null) {
      return new InetSocketAddress(host, Integer.parseInt(port));
    } else {
      return null;
    }
  }
}
