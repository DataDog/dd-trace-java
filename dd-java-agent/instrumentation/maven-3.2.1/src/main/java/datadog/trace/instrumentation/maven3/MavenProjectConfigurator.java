package datadog.trace.instrumentation.maven3;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.SkippableTest;
import datadog.trace.api.civisibility.config.SkippableTestsSerializer;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.DatadogClassLoader;
import datadog.trace.util.Strings;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.annotation.Nullable;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MavenProjectConfigurator {

  private static final Logger LOGGER = LoggerFactory.getLogger(MavenProjectConfigurator.class);

  static final MavenProjectConfigurator INSTANCE = new MavenProjectConfigurator();

  private static final String MAVEN_COMPILER_PLUGIN_KEY =
      "org.apache.maven.plugins:maven-compiler-plugin";

  private static final String DATADOG_GROUP_ID = "com.datadoghq";
  private static final String DATADOG_JAVAC_PLUGIN_ARTIFACT_ID = "dd-javac-plugin";
  private static final String DATADOG_JAVAC_PLUGIN_CLIENT_ARTIFACT_ID = "dd-javac-plugin-client";
  private static final String LOMBOK_GROUP_ID = "org.projectlombok";
  private static final String LOMBOK_ARTIFACT_ID = "lombok";
  private static final String JAVAC_COMPILER_ID = "javac";
  private static final String DATADOG_COMPILER_PLUGIN_ID = "DatadogCompilerPlugin";

  private static final ComparableVersion ANNOTATION_PROCESSOR_PATHS_SUPPORTED_VERSION =
      new ComparableVersion("3.5");

  private static final ComparableVersion LATE_SUBSTITUTION_SUPPORTED_VERSION =
      new ComparableVersion("2.17");
  private static final String JACOCO_EXCL_CLASS_LOADERS_PROPERTY = "jacoco.exclClassLoaders";

  public void configureTracer(
      MojoExecution mojoExecution,
      Map<String, String> propagatedSystemProperties,
      @Nullable Collection<SkippableTest> skippableTests) {
    Xpp3Dom configuration = mojoExecution.getConfiguration();

    Xpp3Dom forkCount = configuration.getChild("forkCount");
    if (forkCount != null && "0".equals(forkCount.getValue())) {
      // tests will be executed inside this JVM, no need for additional configuration
      return;
    }

    Plugin plugin = mojoExecution.getPlugin();
    String pluginVersion = plugin.getVersion();
    ComparableVersion pluginVersionParsed =
        new ComparableVersion(pluginVersion != null ? pluginVersion : "");

    String projectWideArgLine;
    if (pluginVersionParsed.compareTo(LATE_SUBSTITUTION_SUPPORTED_VERSION) >= 0) {
      // include project-wide argLine
      // (it might be modified by other plugins earlier in the build cycle, e.g. by Jacoco)
      projectWideArgLine = "@{argLine} ";
    } else {
      projectWideArgLine = "${argLine} ";
    }
    StringBuilder modifiedArgLine = new StringBuilder(projectWideArgLine);

    // propagate to child process all "dd." system properties available in current process
    for (Map.Entry<String, String> e : propagatedSystemProperties.entrySet()) {
      modifiedArgLine.append("-D").append(e.getKey()).append('=').append(e.getValue()).append(" ");
    }

    if (skippableTests != null && !skippableTests.isEmpty()) {
      String skippableTestsString = SkippableTestsSerializer.serialize(skippableTests);
      modifiedArgLine
          .append("-D")
          .append(
              Strings.propertyNameToSystemPropertyName(
                  CiVisibilityConfig.CIVISIBILITY_SKIPPABLE_TESTS))
          .append('=')
          .append(skippableTestsString)
          .append(" ");
    }

    Integer ciVisibilityDebugPort = Config.get().getCiVisibilityDebugPort();
    if (ciVisibilityDebugPort != null) {
      modifiedArgLine
          .append("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=")
          .append(ciVisibilityDebugPort)
          .append(" ");
    }

    File agentJar = Config.get().getCiVisibilityAgentJarFile();
    modifiedArgLine.append("-javaagent:").append(agentJar.toPath());

    Map<String, PluginExecution> pluginExecutions = plugin.getExecutionsAsMap();
    PluginExecution pluginExecution = pluginExecutions.get(mojoExecution.getExecutionId());
    Xpp3Dom executionConfiguration = (Xpp3Dom) pluginExecution.getConfiguration();

    String argLine = MavenUtils.getConfigurationValue(executionConfiguration, "argLine");
    if (argLine != null) {
      modifiedArgLine.append(" ").append(argLine);
    }

    Xpp3Dom updatedExecutionConfiguration =
        MavenUtils.setConfigurationValue(
            modifiedArgLine.toString(), executionConfiguration, "argLine");
    pluginExecution.setConfiguration(updatedExecutionConfiguration);
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

      ComparableVersion mavenPluginVersion =
          new ComparableVersion(
              compilerPlugin.getVersion() != null ? compilerPlugin.getVersion() : "");

      if (mavenPluginVersion.compareTo(ANNOTATION_PROCESSOR_PATHS_SUPPORTED_VERSION) >= 0) {
        String lombokVersion = getLombokVersion(projectDependencies);
        if (lombokVersion != null) {
          configuration =
              addAnnotationProcessorPath(
                  configuration, LOMBOK_GROUP_ID, LOMBOK_ARTIFACT_ID, lombokVersion);
        }
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

  private String getLombokVersion(List<Dependency> dependencies) {
    for (Dependency dependency : dependencies) {
      if (LOMBOK_GROUP_ID.equals(dependency.getGroupId())
          && LOMBOK_ARTIFACT_ID.equals(dependency.getArtifactId())) {
        return dependency.getVersion();
      }
    }
    return null;
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

  void configureJacoco(MavenProject project) {
    Properties projectProperties = project.getProperties();

    String currentValue = projectProperties.getProperty(JACOCO_EXCL_CLASS_LOADERS_PROPERTY);
    String updatedValue =
        Strings.isNotBlank(currentValue)
            ? currentValue + ":" + DatadogClassLoader.class.getName()
            : DatadogClassLoader.class.getName();

    projectProperties.setProperty(JACOCO_EXCL_CLASS_LOADERS_PROPERTY, updatedValue);

    String jacocoPluginVersion = Config.get().getCiVisibilityJacocoPluginVersion();
    if (jacocoPluginVersion != null) {
      configureJacocoPlugin(project, jacocoPluginVersion);
    }
  }

  private static void configureJacocoPlugin(MavenProject project, String jacocoPluginVersion) {
    if (project.getPlugin("org.jacoco:jacoco-maven-plugin") != null) {
      return; // jacoco is already configured for this project
    }

    Plugin jacocoPlugin = new Plugin();
    jacocoPlugin.setGroupId("org.jacoco");
    jacocoPlugin.setArtifactId("jacoco-maven-plugin");
    jacocoPlugin.setVersion(jacocoPluginVersion);

    // a little trick to avoid triggering
    // Maven Enforcer Plugin's "Require Plugin Versions" rule:
    // we're making it look like version was specified explicitly
    // in the project's config files
    InputSource versionSource = new InputSource();
    versionSource.setLocation("injected-by-dd-java-agent");
    versionSource.setModelId("injected-by-dd-java-agent");
    InputLocation versionLocation = new InputLocation(0, 0, versionSource);
    jacocoPlugin.setLocation("version", versionLocation);

    PluginExecution execution = new PluginExecution();
    execution.addGoal("prepare-agent");
    jacocoPlugin.addExecution(execution);

    List<String> instrumentedPackages = Config.get().getCiVisibilityJacocoPluginIncludes();
    if (instrumentedPackages != null && !instrumentedPackages.isEmpty()) {
      configureJacocoInstrumentedPackages(execution, instrumentedPackages);
    } else {
      List<String> excludedPackages = Config.get().getCiVisibilityJacocoPluginExcludes();
      if (excludedPackages != null && !excludedPackages.isEmpty()) {
        configureExcludedPackages(execution, excludedPackages);
      }
    }

    Build build = project.getBuild();
    build.addPlugin(jacocoPlugin);
  }

  private static void configureJacocoInstrumentedPackages(
      PluginExecution execution, List<String> instrumentedPackages) {
    Xpp3Dom includes = new Xpp3Dom("includes");
    for (String instrumentedPackage : instrumentedPackages) {
      Xpp3Dom include = new Xpp3Dom("include");
      include.setValue(instrumentedPackage);
      includes.addChild(include);
    }

    Xpp3Dom configuration = new Xpp3Dom("configuration");
    configuration.addChild(includes);

    execution.setConfiguration(configuration);
  }

  private static void configureExcludedPackages(
      PluginExecution execution, List<String> excludedPackages) {
    Xpp3Dom excludes = new Xpp3Dom("excludes");
    for (String excludedPackage : excludedPackages) {
      Xpp3Dom exclude = new Xpp3Dom("exclude");
      exclude.setValue(excludedPackage);
      excludes.addChild(exclude);
    }

    Xpp3Dom configuration = new Xpp3Dom("configuration");
    configuration.addChild(excludes);

    execution.setConfiguration(configuration);
  }
}
