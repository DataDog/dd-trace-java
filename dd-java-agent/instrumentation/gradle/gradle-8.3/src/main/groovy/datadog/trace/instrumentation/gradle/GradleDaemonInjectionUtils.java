package datadog.trace.instrumentation.gradle;

import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_INJECTED_TRACER_VERSION;
import static datadog.trace.util.ConfigStrings.propertyNameToSystemPropertyName;

import datadog.environment.SystemProperties;
import datadog.trace.api.Config;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class GradleDaemonInjectionUtils {

  public static Map<String, String> addJavaagentToGradleDaemonProperties(
      Map<String, String> jvmOptions) {
    if (SystemProperties.get(propertyNameToSystemPropertyName(CIVISIBILITY_INJECTED_TRACER_VERSION))
        != null) {
      // This Gradle launcher is started by a process that is itself instrumented,
      // most likely this is a Gradle build using Gradle Test Kit to fork another Gradle instance
      // (e.g. to test a Gradle plugin).
      // We don't want to instrument/trace such "nested" Gradle instances
      return jvmOptions;
    }

    File agentJar = Config.get().getCiVisibilityAgentJarFile();
    Path agentJarPath = agentJar.toPath();
    StringBuilder agentArg = new StringBuilder("-javaagent:").append(agentJarPath).append('=');

    for (Map.Entry<String, String> p : SystemProperties.asStringMap().entrySet()) {
      String propertyName = p.getKey();
      String propertyValue = p.getValue();
      if (propertyName.startsWith(Config.PREFIX)) {
        agentArg
            .append(propertyName)
            .append("='")
            .append(propertyValue.replace("'", "'\\''"))
            .append("',");
      }
    }

    // creating a new map in case jvmOptions is immutable
    Map<String, String> updatedJvmOptions = new HashMap<>(jvmOptions);
    updatedJvmOptions.merge("org.gradle.jvmargs", agentArg.toString(), (o, n) -> o + " " + n);
    return updatedJvmOptions;
  }
}
