package datadog.gradle.plugin.csi

import org.gradle.api.JavaVersion
import org.gradle.api.NamedDomainObjectSet
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.Directory
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME
import org.gradle.api.plugins.JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME
import org.gradle.api.plugins.JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME
import org.gradle.api.plugins.JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.testing.base.TestingExtension
import javax.inject.Inject

private const val CALL_SITE_INSTRUMENTER_MAIN_CLASS = "datadog.trace.plugin.csi.PluginApplication"
const val CSI = "csi"
const val CSI_SOURCE_SET = CSI

abstract class CallSiteInstrumentationPlugin : Plugin<Project> {
  @get:Inject
  abstract val javaToolchains: JavaToolchainService

  override fun apply(project: Project) {
    project.pluginManager.apply(JavaPlugin::class)

    // Create plugin extension
    val csiExtension = project.extensions.create<CallSiteInstrumentationExtension>(CSI)

    configureSourceSets(project)
    val generator = registerGenerateCallSiteTask(project, csiExtension, project.tasks.named<AbstractCompile>("compileJava"))
    configureTestConfigurations(project, generator.flatMap { it.destinationDirectory })
  }

  private fun configureSourceSets(project: Project) {
    // create a new source set for the csi files
    val sourceSets = project.sourceSets
    val mainSourceSet = sourceSets.named(MAIN_SOURCE_SET_NAME).get()
    val csiSourceSet = sourceSets.create(CSI_SOURCE_SET) {
      compileClasspath += mainSourceSet.output // mainly needed for the plugin tests
      annotationProcessorPath += mainSourceSet.annotationProcessorPath
    }

    project.configurations.named(csiSourceSet.compileClasspathConfigurationName) {
      extendsFrom(project.configurations.named(mainSourceSet.compileClasspathConfigurationName).get())
    }

    project.tasks.named<AbstractCompile>(csiSourceSet.getCompileTaskName("java")) {
      sourceCompatibility = JavaVersion.VERSION_1_8.toString()
      targetCompatibility = JavaVersion.VERSION_1_8.toString()
    }

    // include classes in final JAR
    project.tasks.named<Jar>("jar") {
      from(csiSourceSet.output.classesDirs)
    }
  }

  private fun configureTestConfigurations(project: Project, generatedSources: Provider<Directory>) {
    val csiOutput = project.sourceSets.named(CSI_SOURCE_SET).map { it.output }
    // Keep CSI output outside the dependency configurations inspected by the generator.
    project.pluginManager.withPlugin("jvm-test-suite") {
      project.extensions.getByType<TestingExtension>().suites.withType<JvmTestSuite>().configureEach {
        project.logger.info("Configuring jvm test suite '{}' to use CSI output", name)
        sources {
          compileClasspath += project.files(csiOutput, generatedSources)
          runtimeClasspath += project.files(csiOutput, generatedSources)
        }
      }
    }
  }

  private fun registerGenerateCallSiteTask(
    project: Project,
    csiExtension: CallSiteInstrumentationExtension,
    mainCompileTask: TaskProvider<AbstractCompile>
  ): TaskProvider<GenerateCallSiteTask> {
    val genTaskName = mainCompileTask.name.replace("compile", "generateCallSite")
    val pluginJarFile = project.layout
      .dir(csiExtension.rootFolder.orElse(project.rootDir))
      .map {
        it.file("buildSrc/call-site-instrumentation-plugin/build/libs/call-site-instrumentation-plugin-all.jar")
      }

    val callSiteGeneratorTask = project.tasks.register<GenerateCallSiteTask>(genTaskName) {
      // Task description
      group = "call site instrumentation"
      description = "Generates call sites from ${mainCompileTask.name}"

      // Remote Debug
      if (project.providers.gradleProperty("debugCsiJar").isPresent) {
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:5005")
      }

      // Task input & output
      sourceDirectory.convention(csiExtension.srcFolder)
      classesDirectory.convention(mainCompileTask.flatMap { it.destinationDirectory })
      destinationDirectory.convention(csiExtension.targetFolder)
      suffix.convention(csiExtension.suffix)
      reporters.convention(csiExtension.reporters)

      val csiOutput = project.sourceSets.named(CSI_SOURCE_SET).map { it.output }
      val currentProjectPath = project.buildTreePath
      val dependencyClasspaths = project.defaultConfigurations.map { configuration ->
        configuration.incoming.artifactView {
          // Own test fixtures depend on classes, which already depends on this generator.
          componentFilter {
            it !is ProjectComponentIdentifier || it.buildTreePath != currentProjectPath
          }
        }.files
      }

      analysisClasspath.from(
        project.files(
          // SourceSetOutput also depends on classes, which depends on this generator.
          project.sourceSets.named(MAIN_SOURCE_SET_NAME).map { it.output.classesDirs },
          project.tasks.named<ProcessResources>(JavaPlugin.PROCESS_RESOURCES_TASK_NAME).map { it.destinationDir },
          project.sourceSets.named(MAIN_SOURCE_SET_NAME).map { it.output.dirs },
          dependencyClasspaths,
          csiExtension.additionalPaths,
        ).minus(
          project.files(
            csiOutput,
            csiExtension.targetFolder,
            destinationDirectory
          )
        )
      )

      inputs.property("csi.javaVersion", csiExtension.javaVersion)
      // Include inherited locale defaults and values supplied by JVM argument providers.
      inputs.property("csi.jvmArgs", project.provider { allJvmArgs })

      // CSI resolves platform classes through the selected JVM, outside its classpath inputs.
      inputs.property(
        "csi.jvmIdentity",
        javaLauncher.map { launcher ->
          with(launcher.metadata) {
            mapOf(
              "vendor" to vendor,
              "runtimeVersion" to javaRuntimeVersion,
              "vmVersion" to jvmVersion,
              "operatingSystem" to System.getProperty("os.name"),
              "architecture" to System.getProperty("os.arch"),
            )
          }
        }
      )

      // JavaExec configuration
      javaLauncher.set(
        javaToolchains.launcherFor {
          languageVersion.set(csiExtension.javaVersion)
        }
      )
      jvmArgumentProviders.add { csiExtension.jvmArgs.get() }
      classpath(pluginJarFile)
      mainClass.set(CALL_SITE_INSTRUMENTER_MAIN_CLASS)
    }

    val sourceSets = project.sourceSets
    // Make source generation happens when calling `classes` as a developer convenience,
    // but it's not required, as the build is correctly wired.
    sourceSets.named(MAIN_SOURCE_SET_NAME) {
      project.tasks.named(classesTaskName) {
        dependsOn(callSiteGeneratorTask)
      }
    }

    // Compile generated sources
    sourceSets.named(CSI_SOURCE_SET) {
      java.srcDir(callSiteGeneratorTask.flatMap { it.destinationDirectory })
    }
    return callSiteGeneratorTask
  }

  private val Project.defaultConfigurations: NamedDomainObjectSet<Configuration>
    get() = project.configurations.matching {
      // Includes all main* source sets, but only the test (as wee don;t want other )
      // * For main => runtimeClasspath, compileClasspath
      // * For test => testRuntimeClasspath, testCompileClasspath
      // * For other main* => "main_javaXXRuntimeClasspath", "main_javaXXCompileClasspath"

      when (it.name) {
        // Regular main and test source sets
        RUNTIME_CLASSPATH_CONFIGURATION_NAME,
        COMPILE_CLASSPATH_CONFIGURATION_NAME,
        TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME,
        TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME -> true

        else -> false
      }
    }

  private val Project.sourceSets: SourceSetContainer
    get() = project.extensions.getByType<JavaPluginExtension>().sourceSets
}
