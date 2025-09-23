package datadog.gradle.plugin

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.inject.Inject

private const val CALL_SITE_INSTRUMENTER_MAIN_CLASS = "datadog.trace.plugin.csi.PluginApplication"
private const val CALL_SITE_CLASS_SUFFIX = "CallSite"
private const val CALL_SITE_CONSOLE_REPORTER = "CONSOLE"
private const val CALL_SITE_ERROR_CONSOLE_REPORTER = "ERROR_CONSOLE"

/**
 * This extension allows to configure the Call Site Instrumenter plugin execution.
 */
abstract class CallSiteInstrumentationExtension @Inject constructor(objectFactory: ObjectFactory, layout: ProjectLayout) {
  /**
   * The location of the source code to generate call site ({@code <project>/src/main/java} by default).
   */
  val srcFolder: DirectoryProperty = objectFactory.directoryProperty().convention(
    layout.projectDirectory.dir("src").dir("main").dir("java")
  )
  /**
   * The location to generate call site source code ({@code <project>/build/generated/sources/csi} by default).
   */
  val targetFolder: DirectoryProperty = objectFactory.directoryProperty().convention(
    layout.buildDirectory.dir("generated${File.separatorChar}sources${File.separatorChar}csi")
  )
  /**
   * The generated call site source file suffix (#CALL_SITE_CLASS_SUFFIX by default).
   */
  val suffix: Property<String> = objectFactory.property(String::class.java).convention(CALL_SITE_CLASS_SUFFIX)
  /**
   * The reporters to use after call site instrumenter run (only #CALL_SITE_CONSOLE_REPORTER and #CALL_SITE_ERROR_CONSOLE_REPORTER supported for now).
   */
  val reporters: ListProperty<String> = objectFactory.listProperty(String::class.java).convention(listOf(
    CALL_SITE_ERROR_CONSOLE_REPORTER
  ))
  /**
   * The location of the dd-trace-java project to look for the call site instrumenter (optional, current project root folder used if not set).
   */
  abstract val rootFolder: Property<File>

  /**
   * The JVM to use to run the call site instrumenter (optional, default JVM used if not set).
   */
  abstract val javaVersion: Property<JavaLanguageVersion>

  /**
   * The JVM arguments to run the call site instrumenter.
   */
  val jvmArgs: ListProperty<String> = objectFactory.listProperty(String::class.java).convention(listOf("-Xmx128m", "-Xms64m"))
}

abstract class CallSiteInstrumentationPlugin : Plugin<Project>{
  @get:Inject
  abstract val javaToolchains: JavaToolchainService

  override fun apply(project: Project) {
    // Create plugin extension
    val extension = project.extensions.create("csi", CallSiteInstrumentationExtension::class.java)
    project.afterEvaluate {
      configureSourceSets(project, extension)
      createTasks(project, extension)
    }
  }

  private fun configureSourceSets(project: Project, extension: CallSiteInstrumentationExtension) {
    // create a new source set for the csi files
    val targetFolder = newBuildFolder(project, extension.targetFolder.get().asFile.toString())
    val sourceSets = getSourceSets(project)
    val mainSourceSet = sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME).get()
    val csiSourceSet = sourceSets.create("csi") {
      compileClasspath += mainSourceSet.output // mainly needed for the plugin tests
      annotationProcessorPath += mainSourceSet.annotationProcessorPath
      java.srcDir(targetFolder)

    }
    project.configurations.named(csiSourceSet.compileClasspathConfigurationName) {
      extendsFrom(project.configurations.named(mainSourceSet.compileClasspathConfigurationName).get())
    }

    project.tasks.named(csiSourceSet.getCompileTaskName("java"), AbstractCompile::class.java).configure {
      sourceCompatibility = JavaVersion.VERSION_1_8.toString()
      targetCompatibility = JavaVersion.VERSION_1_8.toString()
    }

    // add csi classes to test classpath
    sourceSets.named(SourceSet.TEST_SOURCE_SET_NAME) {
      compileClasspath += csiSourceSet.output.classesDirs
      runtimeClasspath += csiSourceSet.output.classesDirs
    }
    project.dependencies.add("testImplementation", csiSourceSet.output)

    // include classes in final JAR
    project.tasks.named("jar", Jar::class.java) {
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

  private fun getSourceSets(project: Project): SourceSetContainer {
    return project.extensions.getByType(JavaPluginExtension::class.java).sourceSets
  }

  private fun createTasks(project: Project, extension: CallSiteInstrumentationExtension) {
    registerGenerateCallSiteTask(project, extension, "compileJava")
    val targetFolder = extension.targetFolder.get().asFile
    project.tasks.withType(AbstractCompile::class.java).matching {
      task -> task.name.startsWith("compileTest")
    }.configureEach {
      inputs.dir(extension.targetFolder)
      classpath += project.files(targetFolder)
    }
    project.tasks.withType(Test::class.java).configureEach {
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
                                           extension: CallSiteInstrumentationExtension,
                                           compileTaskName: String) {
    val taskName = compileTaskName.replace("compile", "generateCallSite")
    val rootFolder = extension.rootFolder.getOrElse(project.rootDir)
    val pluginJarFile = Paths.get(
      rootFolder.toString(),
      "buildSrc",
      "call-site-instrumentation-plugin",
      "build",
      "libs",
      "call-site-instrumentation-plugin-all.jar"
    ).toFile()
    val compileTask = project.tasks.named(compileTaskName, AbstractCompile::class.java)
    val callSiteGeneratorTask = project.tasks.register(taskName, JavaExec::class.java) {
      // Task description
      group = "call site instrumentation"
      description = "Generates call sites from ${compileTaskName}"
      // Task input & output
      val output = extension.targetFolder
      val inputProvider = compileTask.map { it.destinationDirectory.get() }
      inputs.dir(inputProvider)
      outputs.dir(output)
      // JavaExec configuration
      if (extension.javaVersion.isPresent) {
        configureLanguage(this, extension.javaVersion.get())
      }
      jvmArgumentProviders.add({ extension.jvmArgs.get() })
      classpath(pluginJarFile)
      mainClass.set(CALL_SITE_INSTRUMENTER_MAIN_CLASS)
      // Write the call site instrumenter arguments into a temporary file
      doFirst {
        val programClassPath = getProgramClasspath(project).map { it.toString() }
        val arguments = listOf(
          extension.srcFolder.get().asFile.toString(),
          inputProvider.get().asFile.toString(),
          output.get().asFile.toString(),
          extension.suffix.get(),
          extension.reporters.get().joinToString(",")
        ) + programClassPath

        val argumentFile = newTempFile(temporaryDir, "call-site-arguments")
        Files.write(argumentFile.toPath(), arguments)
        args(argumentFile.toString())
      }

      // make task depends on compile
      dependsOn(compileTask)
    }

    // make all sourcesets' class tasks depend on call site generator
    val sourceSets = getSourceSets(project)
    sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) {
      project.tasks.named(classesTaskName) {
        dependsOn(callSiteGeneratorTask)
      }
    }

    // compile generated sources
    sourceSets.named("csi") {
      project.tasks.named(compileJavaTaskName) {
        dependsOn(callSiteGeneratorTask)
      }
    }
  }

  private fun getProgramClasspath(project: Project): List<File> {
    val classpath = ArrayList<File>()
    // 1. Compilation outputs
    project.tasks.withType(AbstractCompile::class.java)
      .map { it.destinationDirectory.asFile.get() }
      .forEach(classpath::add)
    // 2. Compile time dependencies
    project.tasks.withType(AbstractCompile::class.java)
      .flatMap { it.classpath }
      .forEach(classpath::add)
    // 3. Test time dependencies
    project.tasks.withType(Test::class.java)
      .flatMap { it.classpath }
      .forEach(classpath::add)
    return classpath
  }
}
