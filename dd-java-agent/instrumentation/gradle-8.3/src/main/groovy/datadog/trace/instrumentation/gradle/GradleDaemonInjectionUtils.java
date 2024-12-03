package datadog.trace.instrumentation.gradle;

import datadog.trace.api.Config;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class GradleDaemonInjectionUtils {

  public static Map<String, String> addJavaagentToGradleDaemonProperties(
      Map<String, String> jvmOptions) {
    File agentJar = Config.get().getCiVisibilityAgentJarFile();
    Path agentJarPath = agentJar.toPath();

    StringBuilder agentArg = new StringBuilder("-javaagent:").append(agentJarPath).append('=');

    Properties systemProperties = System.getProperties();
    for (Map.Entry<Object, Object> e : systemProperties.entrySet()) {
      String propertyName = (String) e.getKey();
      Object propertyValue = e.getValue();
      if (propertyName.startsWith(Config.PREFIX)) {
        agentArg.append(propertyName).append('=').append(propertyValue).append(',');
      }
    }

    // creating a new map in case jvmOptions is immutable
    Map<String, String> updatedJvmOptions = new HashMap<>(jvmOptions);
    updatedJvmOptions.merge("org.gradle.jvmargs", agentArg.toString(), (o, n) -> o + " " + n);
    return updatedJvmOptions;
  }
}
