package datadog.trace.instrumentation.gradle.legacy

import datadog.trace.api.Config
import datadog.trace.api.civisibility.domain.BuildSessionSettings
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.bootstrap.DatadogClassLoader
import datadog.trace.util.ConfigStrings
import datadog.trace.util.Strings
import org.gradle.api.Project
import org.gradle.api.Task

import java.nio.file.Files
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Applies CI Visibility configuration to instrumented Gradle projects:
 * <ul>
 *  <li>configures Java compilation tasks to use DD Javac Plugin</li>
 *  <li>configures forked test processes to run with tracer attached</li>
 * </ul>
 *
 * <p>
 * This class is written in Groovy to circumvent compile-time safety checks,
 * since some Gradle classes that are Java-specific are not available in
 * the classloader that loads this instrumentation code
 * (the classes are available in a child CL, but injecting instrumentation code there
 * is troublesome, since there seems to be no convenient place to hook into).
 *
 * <p>
 * Another reason compile-time checks would introduce unnecessary complexity is
 * that depending on the Gradle version, different calls have to be made
 * to achieve the same result (in particular, when configuring dependencies).
 */
class GradleProjectConfigurator {

  /*
   * Each Groovy Closure in here is a separate class.
   * When adding or removing a closure, be sure to update {@link GradleBuildListenerInstrumentation#helperClassNames()}
   */

  public static final GradleProjectConfigurator INSTANCE = new GradleProjectConfigurator()

  private static final String JACOCO_PLUGIN_ID = 'jacoco'

  void configureTracer(Task task, Map<String, String> propagatedSystemProperties) {
    def config = Config.get()
    if (!config.isCiVisibilityAutoConfigurationEnabled()) {
      return
    }

    List<String> jvmArgs = new ArrayList<>(task.jvmArgs != null ? task.jvmArgs : Collections.<String> emptyList())

    // propagate to child process all "dd." system properties available in current process
    for (def e : propagatedSystemProperties.entrySet()) {
      jvmArgs.add("-D" + e.key + '=' + e.value)
    }

    def ciVisibilityDebugPort = config.ciVisibilityDebugPort
    if (ciVisibilityDebugPort != null) {
      jvmArgs.add(
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address="
        + ciVisibilityDebugPort)
    }

    String additionalArgs = config.ciVisibilityAdditionalChildProcessJvmArgs
    if (additionalArgs != null) {
      def project = task.getProject()
      def projectProperties = project.getProperties()
      def processedArgs = replaceProjectProperties(additionalArgs, projectProperties)
      def splitArgs = processedArgs.split(" ")
      jvmArgs.addAll(splitArgs)
    }

    String taskPath = task.getPath()
    jvmArgs.add("-D" + ConfigStrings.propertyNameToSystemPropertyName(CiVisibilityConfig.CIVISIBILITY_MODULE_NAME) + '=' + taskPath)

    jvmArgs.add("-javaagent:" + config.ciVisibilityAgentJarFile.toPath())

    // be sure to use setJvmArgs() and not jvmArgs()
    // as the latter will add the arguments rather than replacing them
    task.setJvmArgs(jvmArgs)
  }

  private static final Pattern PROJECT_PROPERTY_REFERENCE = Pattern.compile("\\\$\\{([^}]+)\\}")

  static String replaceProjectProperties(String s, Map<String, ?> projectProperties) {
    StringBuffer output = new StringBuffer()
    Matcher matcher = PROJECT_PROPERTY_REFERENCE.matcher(s)
    while (matcher.find()) {
      def propertyName = matcher.group(1)
      def propertyValue = projectProperties.get(propertyName)
      matcher.appendReplacement(output, Matcher.quoteReplacement(String.valueOf(propertyValue)))
    }
    matcher.appendTail(output)
    return output.toString()
  }

  void configureProject(Project project, BuildSessionSettings sessionSettings) {
    configureCompilerPlugin(project)
    configureJacoco(project, sessionSettings)
  }

  private void configureCompilerPlugin(Project project) {
    def config = Config.get()
    if (!config.ciVisibilityCompilerPluginAutoConfigurationEnabled) {
      return
    }

    def moduleName = getModuleName(project)

    def closure = { task ->
      if (!task.class.name.contains('JavaCompile')) {
        return
      }

      if (!task.hasProperty('options') || !task.options.hasProperty('compilerArgs') || !task.hasProperty('classpath')) {
        // not a JavaCompile task?
        return
      }

      if (task.options.hasProperty('fork') && task.options.fork
        && task.options.hasProperty('forkOptions') && task.options.forkOptions.executable != null) {
        // a non-standard compiler is likely to be used
        return
      }

      String compilerPluginVersion = config.getCiVisibilityCompilerPluginVersion()
      def ddJavacPlugin = project.configurations.detachedConfiguration(project.dependencies.create("com.datadoghq:dd-javac-plugin:$compilerPluginVersion"))
      def ddJavacPluginClient = project.configurations.detachedConfiguration(project.dependencies.create("com.datadoghq:dd-javac-plugin-client:$compilerPluginVersion"))

      // if instrumented project does dependency verification,
      // we need to exclude the two detached configurations that we're adding
      // as corresponding entries are not in the project's verification-metadata.xml
      if (ddJavacPlugin.resolutionStrategy.respondsTo("disableDependencyVerification")) {
        ddJavacPlugin.resolutionStrategy.disableDependencyVerification()
      }
      if (ddJavacPluginClient.resolutionStrategy.respondsTo("disableDependencyVerification")) {
        ddJavacPluginClient.resolutionStrategy.disableDependencyVerification()
      }

      task.classpath = (task.classpath ?: project.files([])) + ddJavacPluginClient.asFileTree

      if (task.options.hasProperty('annotationProcessorPath')) {
        task.options.annotationProcessorPath = (task.options.annotationProcessorPath ?: project.files([])) + ddJavacPlugin
        task.options.compilerArgs += '-Xplugin:DatadogCompilerPlugin'
      } else {
        // for legacy Gradle versions
        task.options.compilerArgs += ['-processorpath', ddJavacPlugin.asPath, '-Xplugin:DatadogCompilerPlugin']
      }

      if (moduleName != null) {
        task.options.compilerArgs += ['--add-reads', "$moduleName=ALL-UNNAMED"]
      }

      // disable compiler warnings related to annotation processing,
      // since "fail-on-warning" linters might complain about the annotation that the compiler plugin injects
      task.options.compilerArgs += '-Xlint:-processing'
    }

    if (project.tasks.respondsTo("configureEach", Closure)) {
      project.tasks.configureEach closure
    } else {
      // for legacy Gradle versions
      project.tasks.all closure
    }
  }

  private static final Pattern MODULE_NAME_PATTERN = Pattern.compile("\\s*module\\s*((\\w|\\.)+)\\s*\\{")

  private static getModuleName(Project project) {
    def dir = project.getProjectDir().toPath()
    def moduleInfo = dir.resolve(Paths.get("src", "main", "java", "module-info.java"))

    if (Files.exists(moduleInfo)) {
      def lines = Files.lines(moduleInfo)
      for (String line : lines) {
        def m = MODULE_NAME_PATTERN.matcher(line)
        if (m.matches()) {
          return m.group(1)
        }
      }
    }
    return null
  }

  private void configureJacoco(Project project, BuildSessionSettings sessionSettings) {
    def config = Config.get()
    if (!config.isCiVisibilityJacocoPluginVersionProvided() && !sessionSettings.isCoverageReportUploadEnabled()) {
      return
    }

    if (project.plugins.hasPlugin(JACOCO_PLUGIN_ID)) {
      // Jacoco is already configured for this project
      return
    }

    project.apply("plugin": JACOCO_PLUGIN_ID)
    project.jacoco.toolVersion = config.ciVisibilityJacocoPluginVersion

    // if instrumented project does dependency verification,
    // we need to exclude configurations added by Jacoco
    // as corresponding entries are not in the project's verification-metadata.xml
    def jacocoConfigurations = project.configurations.findAll { it.name.startsWith("jacoco") }
    for (def jacocoConfiguration : jacocoConfigurations) {
      if (jacocoConfiguration.resolutionStrategy.respondsTo("disableDependencyVerification")) {
        jacocoConfiguration.resolutionStrategy.disableDependencyVerification()
      }
    }

    def coverageIncludedPackages = sessionSettings.getCoverageIncludedPackages()
    def coverageExcludedPackages = sessionSettings.getCoverageExcludedPackages()
    forEveryTestTask project, { task ->
      task.jacoco.excludeClassLoaders += [DatadogClassLoader.name]

      for (String includedPackage : coverageIncludedPackages) {
        if (Strings.isNotBlank(includedPackage)) {
          task.jacoco.includes += includedPackage
        }
      }
      for (String excludedPackage : coverageExcludedPackages) {
        if (Strings.isNotBlank(excludedPackage)) {
          task.jacoco.excludes += excludedPackage
        }
      }
    }
  }

  private static void forEveryTestTask(Project project, Closure closure) {
    def c = { task ->
      if (GradleUtils.isTestTask(task)) {
        closure task
      }
    }

    if (project.tasks.respondsTo("configureEach", Closure)) {
      project.tasks.configureEach c
    } else {
      // for legacy Gradle versions
      project.tasks.all c
    }
  }
}
