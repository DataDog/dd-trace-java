package datadog.gradle.plugin

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.inject.Inject

private const val CALL_SITE_INSTRUMENTER_MAIN_CLASS = "datadog.trace.plugin.csi.PluginApplication"
private const val CALL_SITE_CLASS_SUFFIX = "CallSite"
private const val CALL_SITE_CONSOLE_REPORTER = "CONSOLE"

abstract class CallSiteInstrumentationExtension @Inject constructor(objectFactory: ObjectFactory) {
  /**
   * The location of the source code to generate call site ({@code src/main/java} by default).
   */
  val srcFolder: Property<String> = objectFactory.property(String::class.java).convention("src${File.separatorChar}main${File.separatorChar}java")
  /**
   * The location to generate call site source code ({@code generated/sources/csi} by default).
   */
  val targetFolder: Property<String> = objectFactory.property(String::class.java).convention("generated${File.separatorChar}sources${File.separatorChar}csi")
  /**
   * The generated call site source file suffix (#CALL_SITE_CLASS_SUFFIX by default).
   */
  val suffix: Property<String> = objectFactory.property(String::class.java).convention(CALL_SITE_CLASS_SUFFIX)
  /**
   * The reporters to use after call site instrumenter run (only #CONSOLE_REPORTER supported for now).
   */
  val reporters: ListProperty<String> = objectFactory.listProperty(String::class.java).convention(listOf(
    CALL_SITE_CONSOLE_REPORTER
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
    val targetFolder = newBuildFolder(project, extension.targetFolder.get())
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
    project.tasks.named("jar", Jar::class.java).configure{
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
    val compileTask: AbstractCompile = project.tasks.named("compileJava").get() as AbstractCompile
    val input = compileTask.destinationDirectory
    val output = project.layout.buildDirectory.dir(extension.targetFolder)
    val targetFolder = output.get().asFile
    createGenerateCallSiteTask(project, extension, compileTask, input, output)
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
                                         input: DirectoryProperty,
                                         output: Provider<Directory>) {
    val taskName = compileTask.name.replace("compile", "generateCallSite")
    val callSiteGeneratorTask = project.tasks.register(taskName, JavaExec::class.java).get()
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    callSiteGeneratorTask.group = "call site instrumentation"
    callSiteGeneratorTask.description = "Generates call sites from ${compileTask.name}"
    if (extension.javaVersion.isPresent) {
      configureLanguage(callSiteGeneratorTask, extension.javaVersion.get())
    }
    callSiteGeneratorTask.setStandardOutput(stdout)
    callSiteGeneratorTask.setErrorOutput(stderr)
    callSiteGeneratorTask.inputs.dir(input)
    callSiteGeneratorTask.outputs.dir(output)
    callSiteGeneratorTask.mainClass.set(CALL_SITE_INSTRUMENTER_MAIN_CLASS)

    val rootFolder = extension.rootFolder.getOrElse(project.rootDir)
    val path = Paths.get(rootFolder.toString(),
    "buildSrc", "call-site-instrumentation-plugin", "build", "libs", "call-site-instrumentation-plugin-all.jar")
    callSiteGeneratorTask.jvmArgs(extension.jvmArgs.get())
    callSiteGeneratorTask.classpath(path.toFile())
    callSiteGeneratorTask.setIgnoreExitValue(true)
    // pass the arguments to the main via file to prevent issues with too long classpaths
    callSiteGeneratorTask.doFirst {
      val argumentFile = newTempFile(temporaryDir, "call-site-arguments")
      val arguments = mutableListOf(
        project.projectDir.toPath().resolve(extension.srcFolder.get()).toString(),
        input.get().asFile.toString(),
        output.get().asFile.toString(),
        extension.suffix.get(),
        extension.reporters.get().joinToString(",")
      )
      arguments.addAll(getProgramClasspath(project).map { it.toString() })
      Files.write(argumentFile.toPath(), arguments)
      callSiteGeneratorTask.args(argumentFile.toString())
    }
    callSiteGeneratorTask.doLast {
      project.logger.info(stdout.toString())
      project.logger.error(stderr.toString())
      if (callSiteGeneratorTask.executionResult.get().exitValue != 0) {
        throw GradleException("Failed to generate call site classes, check task logs for more information")
      }
    }

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
