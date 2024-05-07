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
    val csiSourceSet = sourceSets.create("csi")
    val mainSourceSet = sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME).get()
    val csiConfiguration = project.configurations.named(csiSourceSet.compileClasspathConfigurationName).get()
    val mainConfiguration = project.configurations.named(mainSourceSet.compileClasspathConfigurationName).get()
    csiConfiguration.extendsFrom(mainConfiguration)
    csiSourceSet.compileClasspath += mainSourceSet.output // mainly needed for the plugin tests
    csiSourceSet.annotationProcessorPath += mainSourceSet.annotationProcessorPath
    csiSourceSet.java.srcDir(targetFolder)
    project.getTasksByName(csiSourceSet.getCompileTaskName("java"), false).forEach { task ->
      val compile = task as AbstractCompile
      compile.sourceCompatibility = JavaVersion.VERSION_1_8.toString()
      compile.targetCompatibility = JavaVersion.VERSION_1_8.toString()
    }

    // add csi classes to test classpath
    val testSourceSet = sourceSets.named(SourceSet.TEST_SOURCE_SET_NAME).get()
    testSourceSet.compileClasspath += csiSourceSet.output.classesDirs
    testSourceSet.runtimeClasspath += csiSourceSet.output.classesDirs
    project.dependencies.add("testImplementation", csiSourceSet.output)

    // include classes in final JAR
    project.tasks.named("jar", Jar::class.java).configure {
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
    val compileTask: AbstractCompile = project.tasks.named("compileJava", AbstractCompile::class.java).get()
    val input = compileTask.destinationDirectory
    createGenerateCallSiteTask(project, extension, compileTask, input)
    val targetFolder = extension.targetFolder.get().asFile
    project.tasks.withType(AbstractCompile::class.java).matching {
      task -> task.name.startsWith("compileTest")
    }.configureEach {
      classpath += project.files(targetFolder)
    }
    project.tasks.withType(Test::class.java).configureEach {
      classpath += project.files(targetFolder)
    }
  }

  private fun configureLanguage(task: JavaExec, version: JavaLanguageVersion) {
      task.javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(version)
      })
  }

  private fun createGenerateCallSiteTask(project: Project,
                                         extension: CallSiteInstrumentationExtension,
                                         compileTask: AbstractCompile,
                                         input: DirectoryProperty) {
    val taskName = compileTask.name.replace("compile", "generateCallSite")
    val rootFolder = extension.rootFolder.getOrElse(project.rootDir)
    val pluginJarFile = Paths.get(
      rootFolder.toString(),
      "buildSrc",
      "call-site-instrumentation-plugin",
      "build",
      "libs",
      "call-site-instrumentation-plugin-all.jar"
    ).toFile()
    val programClassPath = getProgramClasspath(project).map { it.toString() }
    val callSiteGeneratorTask = project.tasks.register(taskName, JavaExec::class.java) {
      // Task description
      group = "call site instrumentation"
      description = "Generates call sites from ${compileTask.name}"
      // Task input & output
      val output = extension.targetFolder
      inputs.dir(input)
      outputs.dir(output)
      // JavaExec configuration
      if (extension.javaVersion.isPresent) {
        configureLanguage(this, extension.javaVersion.get())
      }
      jvmArgs(extension.jvmArgs.get())
      classpath(pluginJarFile)
      mainClass.set(CALL_SITE_INSTRUMENTER_MAIN_CLASS)
      // Write the call site instrumenter arguments into a temporary file
      doFirst {
        val argumentFile = newTempFile(temporaryDir, "call-site-arguments")
        val arguments = listOf(
          extension.srcFolder.get().asFile.toString(),
          input.get().asFile.toString(),
          output.get().asFile.toString(),
          extension.suffix.get(),
          extension.reporters.get().joinToString(",")
        ) + programClassPath
        Files.write(argumentFile.toPath(), arguments)
        args(argumentFile.toString())
      }
    }.get()

    // insert task after compile
    callSiteGeneratorTask.dependsOn(compileTask)
    val sourceSets = getSourceSets(project)
    val mainSourceSet = sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME).get()
    project.tasks.named(mainSourceSet.classesTaskName).configure {
      dependsOn(callSiteGeneratorTask)
    }

    // compile generated sources
    val csiSourceSet = sourceSets.named("csi").get()
    project.tasks.named(csiSourceSet.compileJavaTaskName).configure {
      callSiteGeneratorTask.finalizedBy(this)
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
