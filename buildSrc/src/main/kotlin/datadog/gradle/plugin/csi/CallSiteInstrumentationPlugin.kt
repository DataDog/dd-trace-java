package datadog.gradle.plugin.csi

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME
import org.gradle.api.tasks.SourceSet.TEST_SOURCE_SET_NAME
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.inject.Inject

private const val CALL_SITE_INSTRUMENTER_MAIN_CLASS = "datadog.trace.plugin.csi.PluginApplication"
private const val CSI = "csi"
private const val CSI_SOURCE_SET = CSI

abstract class CallSiteInstrumentationPlugin : Plugin<Project>{
  @get:Inject
  abstract val javaToolchains: JavaToolchainService

  override fun apply(project: Project) {
    project.pluginManager.apply(JavaPlugin::class)
    
    // Create plugin extension
    val extension = project.extensions.create<CallSiteInstrumentationExtension>(CSI)
    configureSourceSets(project, extension)
    createTasks(project, extension)
  }

  private fun configureSourceSets(project: Project, extension: CallSiteInstrumentationExtension) {
    // create a new source set for the csi files
    val targetFolder = newBuildFolder(project, extension.targetFolder.get().asFile.toString())
    val sourceSets = project.sourceSets
    val mainSourceSet = sourceSets.named(MAIN_SOURCE_SET_NAME).get()
    val csiSourceSet = sourceSets.create(CSI_SOURCE_SET) {
      compileClasspath += mainSourceSet.output // mainly needed for the plugin tests
      annotationProcessorPath += mainSourceSet.annotationProcessorPath
      java.srcDir(targetFolder)
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

  private fun newBuildFolder(project: Project, name: String): File {
    val folder = project.layout.buildDirectory.dir(name).get().asFile
    if (!folder.exists()) {
      if (!folder.mkdirs()) {
        throw GradleException("Cannot create folder $folder")
      }
    }
    return folder
  }

  private fun newTempFile(folder: File, name: String): File {
    val file = File(folder, name)
    if (!file.exists() && !file.createNewFile()) {
      throw GradleException("Cannot create temporary file: $file")
    }
    file.deleteOnExit()
    return file
  }

  private fun createTasks(project: Project, extension: CallSiteInstrumentationExtension) {
    registerGenerateCallSiteTask(project, extension, project.tasks.named<AbstractCompile>("compileJava"))

    val targetFolder = extension.targetFolder.get().asFile
    project.tasks.withType<AbstractCompile>().matching {
      task -> task.name.startsWith("compileTest")
    }.configureEach {
      inputs.dir(extension.targetFolder)
      classpath += project.files(targetFolder)
    }

    project.tasks.withType<Test>().configureEach {
      inputs.dir(extension.targetFolder)
      classpath += project.files(targetFolder)
    }
  }

  private fun configureLanguage(task: JavaExec, version: JavaLanguageVersion) {
      task.javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(version)
      })
  }

  private fun registerGenerateCallSiteTask(project: Project,
                                           csiExtension: CallSiteInstrumentationExtension,
                                           mainCompileTask: TaskProvider<AbstractCompile>) {
    val genTaskName = mainCompileTask.name.replace("compile", "generateCallSite")
    val pluginJarFile = Paths.get(
      csiExtension.rootFolder.getOrElse(project.rootDir).toString(),
      "buildSrc",
      "call-site-instrumentation-plugin",
      "build",
      "libs",
      "call-site-instrumentation-plugin-all.jar"
    ).toFile()

    val callSiteGeneratorTask = project.tasks.register<JavaExec>(genTaskName) {
      // Task description
      group = "call site instrumentation"
      description = "Generates call sites from ${mainCompileTask.name}"

      // Remote Debug
      // jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:5005")

      // Task input & output
      val output = csiExtension.targetFolder
      val inputProvider = mainCompileTask.flatMap { it.destinationDirectory }
      inputs.dir(inputProvider)
      outputs.dir(output)

      // JavaExec configuration
      if (csiExtension.javaVersion.isPresent) {
        configureLanguage(this, csiExtension.javaVersion.get())
      }
      jvmArgumentProviders.add({ csiExtension.jvmArgs.get() })
      classpath(pluginJarFile)
      mainClass.set(CALL_SITE_INSTRUMENTER_MAIN_CLASS)

      // Write the call site instrumenter arguments into a temporary file
      doFirst {
        val callsitesClassPath = project.objects.fileCollection()
          .from(
            project.sourceSets.named(MAIN_SOURCE_SET_NAME).map { it.output },
            csiExtension.configurations
          )

        if (project.logger.isInfoEnabled) {
          project.logger.info(
            "Aggregated CSI classpath:\n{}",
            callsitesClassPath.toSet().sorted().joinToString("\n") { it.toString() }
          )
        }

        val arguments = buildList {
          add(csiExtension.srcFolder.get().asFile.toString())
          add(inputProvider.get().asFile.toString())
          add(output.get().asFile.toString())
          add(csiExtension.suffix.get())
          add(csiExtension.reporters.get().joinToString(","))

          // module program classpath
          addAll(callsitesClassPath.map { it.toString() })
        }

        val argumentFile = newTempFile(temporaryDir, "call-site-arguments")
        Files.write(argumentFile.toPath(), arguments)
        args(argumentFile.toString())
      }

      // make task depends on compile
      dependsOn(mainCompileTask)
    }

    // make all sourcesets' class tasks depend on call site generator
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

  private val Project.sourceSets: SourceSetContainer
    get() = project.extensions.getByType<JavaPluginExtension>().sourceSets
}
