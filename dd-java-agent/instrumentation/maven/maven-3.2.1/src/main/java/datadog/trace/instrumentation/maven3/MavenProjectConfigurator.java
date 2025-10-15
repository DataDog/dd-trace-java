package datadog.trace.instrumentation.maven3;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.domain.BuildSessionSettings;
import datadog.trace.bootstrap.DatadogClassLoader;
import datadog.trace.util.Strings;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.lifecycle.internal.GoalTask;
import org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator;
import org.apache.maven.lifecycle.internal.LifecycleTask;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
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
  private static final String JAVAC_COMPILER_ID = "javac";
  private static final String DATADOG_COMPILER_PLUGIN_ID = "DatadogCompilerPlugin";

  private static final String JACOCO_EXCL_CLASS_LOADERS_PROPERTY = "jacoco.exclClassLoaders";

  public void configureTracer(
      MavenSession session,
      MavenProject project,
      MojoExecution mojoExecution,
      Map<String, String> systemProperties,
      Config config) {
    if (!config.isCiVisibilityAutoConfigurationEnabled()) {
      return;
    }

    StringBuilder addedArgLine = new StringBuilder();
    for (Map.Entry<String, String> e : systemProperties.entrySet()) {
      addedArgLine
          .append("-D")
          .append(e.getKey())
          .append('=')
          .append(escapeForCommandLine(e.getValue()))
          .append(' ');
    }

    Integer ciVisibilityDebugPort = config.getCiVisibilityDebugPort();
    if (ciVisibilityDebugPort != null) {
      addedArgLine
          .append("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=")
          .append(ciVisibilityDebugPort)
          .append(' ');
    }

    String additionalArgs = config.getCiVisibilityAdditionalChildProcessJvmArgs();
    if (additionalArgs != null) {
      addedArgLine.append(additionalArgs).append(' ');
    }

    File agentJar = config.getCiVisibilityAgentJarFile();
    addedArgLine
        .append("-javaagent:")
        .append(escapeForCommandLine(String.valueOf(agentJar.toPath())));

    String existingArgLine = MavenUtils.getArgLine(session, project, mojoExecution);
    String updatedArgLine =
        (existingArgLine != null ? existingArgLine + " " : "")
            +
            // -javaagent that injects the tracer
            // has to be the last one,
            // since if there are other agents
            // we want to be able to instrument their code
            // (namely Jacoco's)
            addedArgLine;

    Xpp3Dom configuration = mojoExecution.getConfiguration();
    mojoExecution.setConfiguration(
        MavenUtils.setXmlConfigurationValue(updatedArgLine, configuration, "argLine"));
  }

  private String escapeForCommandLine(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  void configureCompilerPlugin(MavenProject project) {
    Config config = Config.get();
    if (!config.isCiVisibilityCompilerPluginAutoConfigurationEnabled()) {
      return;
    }

    Plugin compilerPlugin = project.getPlugin(MAVEN_COMPILER_PLUGIN_KEY);
    if (compilerPlugin == null) {
      return;
    }

    String compilerPluginVersion = config.getCiVisibilityCompilerPluginVersion();
    if (compilerPluginVersion == null) {
      return;
    }

    Xpp3Dom pluginConfiguration = (Xpp3Dom) compilerPlugin.getConfiguration();
    String pluginCompilerId =
        MavenUtils.getXmlConfigurationValue(pluginConfiguration, "compilerId");
    if (pluginCompilerId != null && !JAVAC_COMPILER_ID.equals(pluginCompilerId)) {
      return;
    }

    for (PluginExecution execution : compilerPlugin.getExecutions()) {
      Xpp3Dom configuration = (Xpp3Dom) execution.getConfiguration();

      String compilerId = MavenUtils.getXmlConfigurationValue(configuration, "compilerId");
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
      MavenSession session, MavenProject project, BuildSessionSettings sessionSettings) {
    excludeDatadogClassLoaderFromJacocoInstrumentation(project);
    if (!Config.get().isCiVisibilityJacocoPluginVersionProvided()
        && !sessionSettings.isCoverageReportUploadEnabled()) {
      return;
    }

    if (runsWithJacoco(session, project)) {
      // Jacoco is already configured for this project
      return;
    }

    configureJacocoPlugin(project, sessionSettings);
  }

  private boolean runsWithJacoco(MavenSession session, MavenProject project) {
    try {
      List<String> goals = session.getGoals();
      List<Object> tasks = new ArrayList<>(goals.size());
      for (String goal : goals) {
        if (goal.indexOf(':') >= 0) {
          // a plugin goal, e.g. "surefire:test"
          tasks.add(new GoalTask(goal));
        } else {
          // a lifecycle phase, e.g. "clean"
          tasks.add(new LifecycleTask(goal));
        }
      }
      PlexusContainer container = MavenUtils.getContainer(session);
      LifecycleExecutionPlanCalculator planCalculator =
          container.lookup(LifecycleExecutionPlanCalculator.class);
      MavenExecutionPlan executionPlan =
          planCalculator.calculateExecutionPlan(session, project, tasks);
      for (MojoExecution mojoExecution : executionPlan.getMojoExecutions()) {
        if (MavenUtils.isJacocoInstrumentationExecution(mojoExecution)) {
          return true;
        }
      }
      return false;

    } catch (Exception e) {
      LOGGER.warn("Error while calculation execution plan for project {}", project.getName(), e);
      return false;
    }
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
      MavenProject project, BuildSessionSettings sessionSettings) {
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
        prepareAgentExecution,
        sessionSettings.getCoverageIncludedPackages(),
        sessionSettings.getCoverageExcludedPackages());
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
      PluginExecution execution, List<String> includedPackages, List<String> excludedPackages) {
    Xpp3Dom configuration = new Xpp3Dom("configuration");
    execution.setConfiguration(configuration);

    if (!includedPackages.isEmpty()) {
      Xpp3Dom includes = new Xpp3Dom("includes");
      for (String instrumentedPackage : includedPackages) {
        if (Strings.isNotBlank(instrumentedPackage)) {
          Xpp3Dom include = new Xpp3Dom("include");
          include.setValue(instrumentedPackage);
          includes.addChild(include);
        }
      }
      configuration.addChild(includes);
    }

    if (!excludedPackages.isEmpty()) {
      Xpp3Dom excludes = new Xpp3Dom("excludes");
      for (String excludedPackage : excludedPackages) {
        if (Strings.isNotBlank(excludedPackage)) {
          Xpp3Dom exclude = new Xpp3Dom("exclude");
          exclude.setValue(excludedPackage);
          excludes.addChild(exclude);
        }
      }
      configuration.addChild(excludes);
    }
  }
}
