package datadog.trace.instrumentation.maven3;

import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

class MavenProjectConfigurator {

  static final MavenProjectConfigurator INSTANCE = new MavenProjectConfigurator();

  private static final String MAVEN_COMPILER_PLUGIN_KEY =
      "org.apache.maven.plugins:maven-compiler-plugin";
  private static final String MAVEN_TOOLCHAINS_PLUGIN_KEY =
      "org.apache.maven.plugins:maven-toolchains-plugin";

  private static final String DATADOG_GROUP_ID = "com.datadoghq";
  private static final String DATADOG_JAVAC_PLUGIN_ARTIFACT_ID = "dd-javac-plugin";
  private static final String DATADOG_JAVAC_PLUGIN_CLIENT_ARTIFACT_ID = "dd-javac-plugin-client";
  private static final String JAVAC_COMPILER_ID = "javac";
  private static final String DATADOG_COMPILER_PLUGIN_ID = "DatadogCompilerPlugin";

  private static final MavenPluginVersion ANNOTATION_PROCESSOR_PATHS_SUPPORTED_VERSION =
      MavenPluginVersion.from("3.5");

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

  void configureCompilerPlugin(MavenProject project, String compilerPluginVersion) {
    Plugin compilerPlugin = project.getPlugin(MAVEN_COMPILER_PLUGIN_KEY);
    if (compilerPlugin == null || compilerPluginVersion == null) {
      return;
    }

    Xpp3Dom pluginConfiguration = (Xpp3Dom) compilerPlugin.getConfiguration();
    String pluginCompilerId = MavenUtils.getConfigurationValue(pluginConfiguration, "compilerId");
    if (pluginCompilerId != null && !JAVAC_COMPILER_ID.equals(pluginCompilerId)) {
      return;
    }

    for (PluginExecution execution : compilerPlugin.getExecutions()) {
      Xpp3Dom configuration = (Xpp3Dom) execution.getConfiguration();

      String compilerId = MavenUtils.getConfigurationValue(configuration, "compilerId");
      if (compilerId != null && !JAVAC_COMPILER_ID.equals(compilerId)) {
        // Javac plugin does not work with other compilers
        continue;
      }

      // FIXME add a comment to explain what's happening here
      if (Platform.isJavaVersionAtLeast(16)) {
        if (isCompilerProcessForked(configuration, pluginConfiguration, project)) {
          addPackagesExport(configuration);

        } else if (!isNecessaryPackageExportsPresent()) {
          // FIXME write a log and abort configuration
          return;
        }
      }

      Dependency javacPluginClientDependency = new Dependency();
      javacPluginClientDependency.setGroupId(DATADOG_GROUP_ID);
      javacPluginClientDependency.setArtifactId(DATADOG_JAVAC_PLUGIN_CLIENT_ARTIFACT_ID);
      javacPluginClientDependency.setVersion(compilerPluginVersion);
      project.getDependencies().add(javacPluginClientDependency);

      // FIXME handle Lombok usecase (and test it manually)
      MavenPluginVersion pluginVersion = MavenPluginVersion.from(compilerPlugin.getVersion());
      if (pluginVersion.isLaterThanOrEqualTo(ANNOTATION_PROCESSOR_PATHS_SUPPORTED_VERSION)) {
        configuration =
            MavenUtils.setConfigurationValue(
                DATADOG_GROUP_ID,
                configuration,
                "annotationProcessorPaths",
                "annotationProcessorPath",
                "groupId");
        configuration =
            MavenUtils.setConfigurationValue(
                DATADOG_JAVAC_PLUGIN_ARTIFACT_ID,
                configuration,
                "annotationProcessorPaths",
                "annotationProcessorPath",
                "artifactId");
        configuration =
            MavenUtils.setConfigurationValue(
                compilerPluginVersion,
                configuration,
                "annotationProcessorPaths",
                "annotationProcessorPath",
                "version");

      } else {
        Dependency javacPluginDependency = new Dependency();
        javacPluginDependency.setGroupId(DATADOG_GROUP_ID);
        javacPluginDependency.setArtifactId(DATADOG_JAVAC_PLUGIN_ARTIFACT_ID);
        javacPluginDependency.setVersion(compilerPluginVersion);
        project.getDependencies().add(javacPluginDependency);
      }

      configuration =
          MavenUtils.setConfigurationValue(
              "-Xplugin:" + DATADOG_COMPILER_PLUGIN_ID, configuration, "compilerArgs", "arg");

      execution.setConfiguration(configuration);
    }
  }

  // FIXME test this one manually
  private boolean isCompilerProcessForked(
      Xpp3Dom executionConfiguration, Xpp3Dom pluginConfiguration, MavenProject project) {
    return isCompilerProcessForked(executionConfiguration)
        || isCompilerProcessForked(pluginConfiguration)
        || isUsingToolchainsPlugin(project);
  }

  private boolean isCompilerProcessForked(Xpp3Dom configuration) {
    return "true".equals(MavenUtils.getConfigurationValue(configuration, "fork"))
        || MavenUtils.getConfigurationValue(configuration, "jdkToolchain") != null;
  }

  private boolean isUsingToolchainsPlugin(MavenProject project) {
    Plugin toolchainsPlugin = project.getPlugin(MAVEN_TOOLCHAINS_PLUGIN_KEY);
    return toolchainsPlugin != null;
  }

  // FIXME test this one manually
  private static void addPackagesExport(Xpp3Dom configuration) {
    Xpp3Dom compilerArgs = configuration.getChild("compilerArgs");
    if (compilerArgs == null) {
      compilerArgs = new Xpp3Dom("compilerArgs");
      configuration.addChild(compilerArgs);
    }

    Xpp3Dom apiExport = new Xpp3Dom("arg");
    apiExport.setValue("-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED");
    configuration.addChild(apiExport);

    Xpp3Dom codeExport = new Xpp3Dom("arg");
    codeExport.setValue("-J--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED");
    configuration.addChild(codeExport);

    Xpp3Dom treeExport = new Xpp3Dom("arg");
    treeExport.setValue("-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED");
    configuration.addChild(treeExport);

    Xpp3Dom utilExport = new Xpp3Dom("arg");
    utilExport.setValue("-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");
    configuration.addChild(utilExport);
  }

  private boolean isNecessaryPackageExportsPresent() {
    //    --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
    //    --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
    //    --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
    //    --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
  }
}
