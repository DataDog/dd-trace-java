package datadog.trace.instrumentation.maven3;

import datadog.trace.api.Config;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

public class MavenProjectConfigurator {

  static final MavenProjectConfigurator INSTANCE = new MavenProjectConfigurator();

  void configureTracer(MavenProject project, String pluginKey) {
    Plugin surefirePlugin = project.getPlugin(pluginKey);
    if (surefirePlugin == null) {
      return;
    }

    Xpp3Dom pluginConfiguration = (Xpp3Dom) surefirePlugin.getConfiguration();
    if (pluginConfiguration != null) {
      Xpp3Dom forkCount = pluginConfiguration.getChild("forkCount");
      if (forkCount != null && "0".equals(forkCount.getValue())) {
        // tests will be executed inside this JVM, no need for additional configuration
        return;
      }
    }

    for (PluginExecution execution : surefirePlugin.getExecutions()) {
      Xpp3Dom configuration = (Xpp3Dom) execution.getConfiguration();

      String argLine = MavenUtils.getConfigurationValue(configuration, "argLine");
      StringBuilder modifiedArgLine =
          new StringBuilder(argLine != null ? argLine + System.lineSeparator() : "");

      // propagate to child process all "dd." system properties available in current process
      Properties systemProperties = System.getProperties();
      for (Map.Entry<Object, Object> e : systemProperties.entrySet()) {
        String propertyName = (String) e.getKey();
        if (propertyName.startsWith(Config.PREFIX)) {
          modifiedArgLine
              .append("-D")
              .append(propertyName)
              .append('=')
              .append(e.getValue())
              .append(System.lineSeparator());
        }
      }

      Integer ciVisibilityDebugPort = Config.get().getCiVisibilityDebugPort();
      if (ciVisibilityDebugPort != null) {
        modifiedArgLine
            .append("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=")
            .append(ciVisibilityDebugPort)
            .append(System.lineSeparator());
      }

      File agentJar = getAgentJar();
      modifiedArgLine.append("-javaagent:").append(agentJar.toPath());
      configuration =
          MavenUtils.setConfigurationValue(modifiedArgLine.toString(), configuration, "argLine");

      execution.setConfiguration(configuration);
    }
  }

  private static File getAgentJar() {
    String agentJarUriString = Config.get().getCiVisibilityAgentJarUri();
    if (agentJarUriString == null || agentJarUriString.isEmpty()) {
      throw new IllegalArgumentException("Agent JAR URI is not set in config");
    }

    try {
      URI agentJarUri = new URI(agentJarUriString);
      return new File(agentJarUri);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Malformed agent JAR URI: " + agentJarUriString, e);
    }
  }

  public void configureCompilerPlugin(MavenProject project, String compilerPluginVersion) {}
}
