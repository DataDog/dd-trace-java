package datadog.trace.instrumentation.maven3;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.DatadogClassLoader;
import datadog.trace.util.Strings;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

class MavenProjectConfigurator {

  static final MavenProjectConfigurator INSTANCE = new MavenProjectConfigurator();

  private static final String MAVEN_COMPILER_PLUGIN_KEY =
      "org.apache.maven.plugins:maven-compiler-plugin";

  private static final String DATADOG_GROUP_ID = "com.datadoghq";
  private static final String DATADOG_JAVAC_PLUGIN_ARTIFACT_ID = "dd-javac-plugin";
  private static final String DATADOG_JAVAC_PLUGIN_CLIENT_ARTIFACT_ID = "dd-javac-plugin-client";
  private static final String JAVAC_COMPILER_ID = "javac";
  private static final String DATADOG_COMPILER_PLUGIN_ID = "DatadogCompilerPlugin";

  private static final ComparableVersion LATE_SUBSTITUTION_SUPPORTED_VERSION =
      new ComparableVersion("2.17");
  private static final String JACOCO_EXCL_CLASS_LOADERS_PROPERTY = "jacoco.exclClassLoaders";

  public void configureTracer(
      MavenProject project,
      MojoExecution mojoExecution,
      Map<String, String> propagatedSystemProperties) {
    Xpp3Dom configuration = mojoExecution.getConfiguration();

    Xpp3Dom forkCount = configuration.getChild("forkCount");
    if (forkCount != null && "0".equals(forkCount.getValue())) {
      // tests will be executed inside this JVM, no need for additional configuration
      return;
    }

    StringBuilder modifiedArgLine = new StringBuilder();
    // propagate to child process all "dd." system properties available in current process
    for (Map.Entry<String, String> e : propagatedSystemProperties.entrySet()) {
      modifiedArgLine.append("-D").append(e.getKey()).append('=').append(e.getValue()).append(" ");
    }

    Config config = Config.get();
    Integer ciVisibilityDebugPort = config.getCiVisibilityDebugPort();
    if (ciVisibilityDebugPort != null) {
      modifiedArgLine
          .append("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=")
          .append(ciVisibilityDebugPort)
          .append(" ");
    }

    String additionalArgs = config.getCiVisibilityAdditionalChildProcessJvmArgs();
    if (additionalArgs != null) {
      modifiedArgLine.append(additionalArgs).append(" ");
    }

    String moduleName = MavenUtils.getUniqueModuleName(project, mojoExecution);
    modifiedArgLine
        .append("-D")
        .append(
            Strings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_NAME))
        .append("='")
        .append(moduleName)
        .append("' ");

    File agentJar = config.getCiVisibilityAgentJarFile();
    modifiedArgLine.append("-javaagent:").append(agentJar.toPath());

    Plugin plugin = mojoExecution.getPlugin();
    Map<String, PluginExecution> pluginExecutions = plugin.getExecutionsAsMap();
    PluginExecution pluginExecution = pluginExecutions.get(mojoExecution.getExecutionId());
    Xpp3Dom executionConfiguration = (Xpp3Dom) pluginExecution.getConfiguration();

    String argLine = MavenUtils.getConfigurationValue(executionConfiguration, "argLine");
    boolean projectWideArgLineNeeded = argLine == null || !argLine.contains("{argLine}");

    String finalArgLine =
        (projectWideArgLineNeeded ? getReferenceToProjectWideArgLine(plugin) + " " : "")
            + (argLine != null ? argLine + " " : "")
            +
            // -javaagent that injects the tracer
            // has to be the last one,
            // since if there are other agents
            // we want to be able to instrument their code
            // (namely Jacoco's)
            modifiedArgLine;

    Xpp3Dom updatedExecutionConfiguration =
        MavenUtils.setConfigurationValue(finalArgLine, executionConfiguration, "argLine");
    pluginExecution.setConfiguration(updatedExecutionConfiguration);
  }

  private static String getReferenceToProjectWideArgLine(Plugin plugin) {
    String pluginVersion = plugin.getVersion();
    ComparableVersion pluginVersionParsed =
        new ComparableVersion(pluginVersion != null ? pluginVersion : "");
    if (pluginVersionParsed.compareTo(LATE_SUBSTITUTION_SUPPORTED_VERSION) >= 0) {
      return "@{argLine} ";
    } else {
      return "${argLine} ";
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

      Dependency javacPluginClientDependency = new Dependency();
      javacPluginClientDependency.setGroupId(DATADOG_GROUP_ID);
      javacPluginClientDependency.setArtifactId(DATADOG_JAVAC_PLUGIN_CLIENT_ARTIFACT_ID);
      javacPluginClientDependency.setVersion(compilerPluginVersion);

      List<Dependency> projectDependencies = project.getDependencies();
      addOrUpdate(projectDependencies, javacPluginClientDependency);

      // if <annotationProcessorPaths> section is present,
      // we have to add the plugin in there,
      // otherwise it's best to add it as a regular dependency
      if (configuration != null && configuration.getChild("annotationProcessorPaths") != null) {
        configuration =
            addAnnotationProcessorPath(
                configuration,
                DATADOG_GROUP_ID,
                DATADOG_JAVAC_PLUGIN_ARTIFACT_ID,
                compilerPluginVersion);

      } else {
        Dependency javacPluginDependency = new Dependency();
        javacPluginDependency.setGroupId(DATADOG_GROUP_ID);
        javacPluginDependency.setArtifactId(DATADOG_JAVAC_PLUGIN_ARTIFACT_ID);
        javacPluginDependency.setVersion(compilerPluginVersion);

        addOrUpdate(projectDependencies, javacPluginDependency);
      }

      configuration = addCompilerArg(configuration, "-Xplugin:" + DATADOG_COMPILER_PLUGIN_ID);

      // disable compiler warnings related to annotation processing,
      // since "fail-on-warning" linters might complain about the annotation that the compiler
      // plugin injects
      configuration = addCompilerArg(configuration, "-Xlint:-processing");

      execution.setConfiguration(configuration);
    }
  }

  private static void addOrUpdate(List<Dependency> projectDependencies, Dependency dependency) {
    ComparableVersion dependencyVersion = new ComparableVersion(dependency.getVersion());

    for (Dependency projectDependency : projectDependencies) {
      if (projectDependency.getGroupId().equals(dependency.getGroupId())
          && projectDependency.getArtifactId().equals(dependency.getArtifactId())) {

        ComparableVersion projectDependencyVersion =
            new ComparableVersion(projectDependency.getVersion());
        if (dependencyVersion.compareTo(projectDependencyVersion) > 0) {
          projectDependency.setVersion(dependency.getVersion());
        }

        return;
      }
    }
    projectDependencies.add(dependency);
  }

  private static Xpp3Dom addCompilerArg(Xpp3Dom configuration, String argValue) {
    if (configuration == null) {
      configuration = new Xpp3Dom("configuration");
    }

    Xpp3Dom compilerArgs = configuration.getChild("compilerArgs");
    if (compilerArgs == null) {
      compilerArgs = new Xpp3Dom("compilerArgs");
      configuration.addChild(compilerArgs);
    }

    Xpp3Dom arg = new Xpp3Dom("arg");
    arg.setValue(argValue);
    compilerArgs.addChild(arg);

    return configuration;
  }

  private static Xpp3Dom addAnnotationProcessorPath(
      Xpp3Dom configuration, String groupId, String artifactId, String version) {
    if (configuration == null) {
      configuration = new Xpp3Dom("configuration");
    }

    Xpp3Dom annotationProcessorPaths = configuration.getChild("annotationProcessorPaths");
    if (annotationProcessorPaths == null) {
      annotationProcessorPaths = new Xpp3Dom("annotationProcessorPaths");
      configuration.addChild(annotationProcessorPaths);
    }

    Xpp3Dom annotationProcessorPath = new Xpp3Dom("annotationProcessorPath");

    Xpp3Dom groupIdElement = new Xpp3Dom("groupId");
    groupIdElement.setValue(groupId);
    annotationProcessorPath.addChild(groupIdElement);

    Xpp3Dom artifactIdElement = new Xpp3Dom("artifactId");
    artifactIdElement.setValue(artifactId);
    annotationProcessorPath.addChild(artifactIdElement);

    Xpp3Dom versionElement = new Xpp3Dom("version");
    versionElement.setValue(version);
    annotationProcessorPath.addChild(versionElement);

    annotationProcessorPaths.addChild(annotationProcessorPath);

    return configuration;
  }

  void configureJacoco(
      MavenTestExecution testExecution, ModuleExecutionSettings moduleExecutionSettings) {
    MavenProject project = testExecution.getProject();
    excludeDatadogClassLoaderFromJacocoInstrumentation(project);

    if (!Config.get().isCiVisibilityJacocoPluginVersionProvided()
        || testExecution.isRunsWithJacoco()) {
      return;
    }

    configureJacocoPlugin(project, moduleExecutionSettings);
  }

  private static void excludeDatadogClassLoaderFromJacocoInstrumentation(MavenProject project) {
    String datadogClassLoaderName = DatadogClassLoader.class.getName();

    Properties projectProperties = project.getProperties();
    String currentValue = projectProperties.getProperty(JACOCO_EXCL_CLASS_LOADERS_PROPERTY);
    if (Strings.isNotBlank(currentValue)) {
      if (!currentValue.contains(datadogClassLoaderName)) {
        projectProperties.setProperty(
            JACOCO_EXCL_CLASS_LOADERS_PROPERTY, currentValue + ":" + datadogClassLoaderName);
      }
    } else {
      projectProperties.setProperty(JACOCO_EXCL_CLASS_LOADERS_PROPERTY, datadogClassLoaderName);
    }
  }

  private static void configureJacocoPlugin(
      MavenProject project, ModuleExecutionSettings moduleExecutionSettings) {
    Plugin jacocoPlugin = getJacocoPlugin(project);
    for (PluginExecution execution : jacocoPlugin.getExecutions()) {
      if (execution.getGoals().contains("prepare-agent")) {
        // prepare-agent goal is already configured
        return;
      }
    }

    PluginExecution prepareAgentExecution = new PluginExecution();
    prepareAgentExecution.addGoal("prepare-agent");
    jacocoPlugin.addExecution(prepareAgentExecution);

    configureJacocoInstrumentedPackages(
        prepareAgentExecution, moduleExecutionSettings.getCoverageEnabledPackages());
  }

  private static Plugin getJacocoPlugin(MavenProject project) {
    Plugin existingPlugin = project.getPlugin("org.jacoco:jacoco-maven-plugin");
    if (existingPlugin != null) {
      return existingPlugin;
    }

    Config config = Config.get();

    Plugin jacocoPlugin = new Plugin();
    jacocoPlugin.setGroupId("org.jacoco");
    jacocoPlugin.setArtifactId("jacoco-maven-plugin");
    jacocoPlugin.setVersion(config.getCiVisibilityJacocoPluginVersion());

    // a little trick to avoid triggering
    // Maven Enforcer Plugin's "Require Plugin Versions" rule:
    // we're making it look like version was specified explicitly
    // in the project's config files
    InputSource versionSource = new InputSource();
    versionSource.setLocation("injected-by-dd-java-agent");
    versionSource.setModelId("injected-by-dd-java-agent");
    InputLocation versionLocation = new InputLocation(0, 0, versionSource);
    jacocoPlugin.setLocation("version", versionLocation);

    Build build = project.getBuild();
    build.addPlugin(jacocoPlugin);

    return jacocoPlugin;
  }

  private static void configureJacocoInstrumentedPackages(
      PluginExecution execution, List<String> instrumentedPackages) {
    Xpp3Dom includes = new Xpp3Dom("includes");
    for (String instrumentedPackage : instrumentedPackages) {
      if (Strings.isNotBlank(instrumentedPackage)) {
        Xpp3Dom include = new Xpp3Dom("include");
        include.setValue(instrumentedPackage);
        includes.addChild(include);
      }
    }

    if (includes.getChildCount() > 0) {
      Xpp3Dom configuration = new Xpp3Dom("configuration");
      configuration.addChild(includes);
      execution.setConfiguration(configuration);
    }
  }
}
