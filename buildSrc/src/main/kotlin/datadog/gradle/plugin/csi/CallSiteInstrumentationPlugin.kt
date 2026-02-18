package datadog.gradle.plugin.csi

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.NamedDomainObjectSet
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME
import org.gradle.api.plugins.JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME
import org.gradle.api.tasks.SourceSet.TEST_SOURCE_SET_NAME
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.internal.configuration.problems.projectPathFrom
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.base.TestingExtension
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
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
    configureSourceSets(project, csiExtension)
    registerGenerateCallSiteTask(project, csiExtension, project.tasks.named<AbstractCompile>("compileJava"))
    configureTestConfigurations(project, csiExtension)
  }

  private fun configureSourceSets(project: Project, extension: CallSiteInstrumentationExtension) {
    // create a new source set for the csi files
    val sourceSets = project.sourceSets
    val mainSourceSet = sourceSets.named(MAIN_SOURCE_SET_NAME).get()
    val csiSourceSet = sourceSets.create(CSI_SOURCE_SET) {
      compileClasspath += mainSourceSet.output // mainly needed for the plugin tests
      annotationProcessorPath += mainSourceSet.annotationProcessorPath
      java.srcDir(extension.targetFolder)
    }

    project.configurations.named(csiSourceSet.compileClasspathConfigurationName) {
      extendsFrom(project.configurations.named(mainSourceSet.compileClasspathConfigurationName).get())
    }

    project.tasks.named<AbstractCompile>(csiSourceSet.getCompileTaskName("java")) {
      sourceCompatibility = JavaVersion.VERSION_1_8.toString()
      targetCompatibility = JavaVersion.VERSION_1_8.toString()
    }

    // add csi classes to test classpath
    sourceSets.named(TEST_SOURCE_SET_NAME) {
      compileClasspath += csiSourceSet.output.classesDirs
      runtimeClasspath += csiSourceSet.output.classesDirs
    }
    project.dependencies.add("testImplementation", csiSourceSet.output)

    // include classes in final JAR
    project.tasks.named<Jar>("jar") {
      from(csiSourceSet.output.classesDirs)
    }
  }

  private fun newTempFile(folder: File, name: String): File {
    val file = File(folder, name)
    if (!file.exists() && !file.createNewFile()) {
      throw GradleException("Cannot create temporary file: $file")
    }
    file.deleteOnExit()
    return file
  }

  private fun configureTestConfigurations(project: Project, csiExtension: CallSiteInstrumentationExtension) {
    project.pluginManager.withPlugin("jvm-test-suite") {
      project.extensions.getByType<TestingExtension>().suites.withType<JvmTestSuite>().configureEach {
        project.logger.info("Configuring jvm test suite '{}' to use csiExtension.targetFolder", name)
        dependencies {
          compileOnly.add(project.files(csiExtension.targetFolder))
          runtimeOnly.add(project.files(csiExtension.targetFolder))
        }
      }
    }
  }

  private fun registerGenerateCallSiteTask(
    project: Project,
    csiExtension: CallSiteInstrumentationExtension,
    mainCompileTask: TaskProvider<AbstractCompile>
  ) {
    val genTaskName = mainCompileTask.name.replace("compile", "generateCallSite")
    val pluginJarFile = Paths.get(
      csiExtension.rootFolder.getOrElse(project.rootDir).toString(),
      "buildSrc",
      "call-site-instrumentation-plugin",
      "build",
      "libs",
      "call-site-instrumentation-plugin-all.jar"
    )

    val callSiteGeneratorTask = project.tasks.register<JavaExec>(genTaskName) {
      // Task description
      group = "call site instrumentation"
      description = "Generates call sites from ${mainCompileTask.name}"

      // Remote Debug
      if (project.providers.gradleProperty("debugCsiJar").isPresent) {
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:5005")
      }

      // Task input & output
      val output = csiExtension.targetFolder
      val inputProvider = mainCompileTask.flatMap { it.destinationDirectory }
      inputs.dir(inputProvider)
      inputs.dir(csiExtension.srcFolder)
      inputs.dir(csiExtension.rootFolder).optional()
      inputs.file(pluginJarFile)
      inputs.property("csi.suffix", csiExtension.suffix)
      inputs.property("csi.javaVersion", csiExtension.javaVersion)
      inputs.property("csi.jvmArgs", csiExtension.jvmArgs)
      inputs.property("csi.reporters", csiExtension.reporters)
      outputs.dir(output)

      // JavaExec configuration
      javaLauncher.set(
        javaToolchains.launcherFor {
          languageVersion.set(csiExtension.javaVersion)
        }
      )

      jvmArgumentProviders.add({ csiExtension.jvmArgs.get() })
      classpath(pluginJarFile)
      mainClass.set(CALL_SITE_INSTRUMENTER_MAIN_CLASS)

      // Write the call site instrumenter arguments into a temporary file
      doFirst {
        val callsitesClassPath = project.files(
          project.sourceSets.named(MAIN_SOURCE_SET_NAME).map { it.output },
          project.defaultConfigurations,
          csiExtension.additionalPaths,
        )

        if (logger.isInfoEnabled) {
          logger.info(
            "Aggregated CSI classpath:\n{}",
            callsitesClassPath.toSet().sorted().joinToString("\n") { it.toString() }
          )
        }

        val argFile = buildList {
          add(csiExtension.srcFolder.get().asFile.toString())
          add(inputProvider.get().asFile.toString())
          add(output.get().asFile.toString())
          add(csiExtension.suffix.get())
          add(csiExtension.reporters.get().joinToString(","))

          // module program classpath
          addAll(callsitesClassPath.map { it.toString() })
        }

        val argumentFile = newTempFile(temporaryDir, "call-site-arguments")
        Files.write(argumentFile.toPath(), argFile)
        args(argumentFile.toString())
      }

      // make task depends on compile
      dependsOn(mainCompileTask)
    }

    // make all sourceSets class tasks depend on call site generator
    val sourceSets = project.sourceSets
    sourceSets.named(MAIN_SOURCE_SET_NAME) {
      project.tasks.named(classesTaskName) {
        dependsOn(callSiteGeneratorTask)
      }
    }

    // compile generated sources
    sourceSets.named(CSI_SOURCE_SET) {
      project.tasks.named(compileJavaTaskName) {
        dependsOn(callSiteGeneratorTask)
      }
    }
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
        TEST_SOURCE_SET_NAME + RUNTIME_CLASSPATH_CONFIGURATION_NAME.capitalize(),
        TEST_SOURCE_SET_NAME + COMPILE_CLASSPATH_CONFIGURATION_NAME.capitalize() -> true

        else -> false
      }
    }

  private fun String.capitalize(): String = replaceFirstChar {
    if (it.isLowerCase()) {
      it.titlecase(
        Locale.getDefault()
      )
    } else {
      it.toString()
    }
  }

  private val Project.sourceSets: SourceSetContainer
    get() = project.extensions.getByType<JavaPluginExtension>().sourceSets
}
