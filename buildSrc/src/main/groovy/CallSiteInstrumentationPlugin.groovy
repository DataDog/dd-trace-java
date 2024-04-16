import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

import java.nio.file.Paths

@SuppressWarnings('unused')
@CompileStatic
class CallSiteInstrumentationPlugin implements Plugin<Project> {

  @Override
  void apply(final Project target) {
    target.extensions.create('csi', CallSiteInstrumentationExtension)
    target.afterEvaluate {
      configureSourceSets(target)
      createTasks(target)
    }
  }

  private static void configureSourceSets(final Project target) {
    final extension = target.extensions.getByType(CallSiteInstrumentationExtension)

    // create a new source set for the csi files
    final targetFolder = newBuildFolder(target, extension.targetFolder)
    final sourceSets = getSourceSets(target)
    final csiSourceSet = sourceSets.create('csi')
    final mainSourceSet = sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME).get()
    final csiConfiguration = target.configurations.named(csiSourceSet.compileClasspathConfigurationName).get()
    final mainConfiguration = target.configurations.named(mainSourceSet.compileClasspathConfigurationName).get()
    csiConfiguration.extendsFrom(mainConfiguration)
    csiSourceSet.compileClasspath += mainSourceSet.output // mainly needed for the plugin tests
    csiSourceSet.annotationProcessorPath += mainSourceSet.annotationProcessorPath
    csiSourceSet.java.srcDir(targetFolder)
    target.getTasksByName(csiSourceSet.getCompileTaskName('java'), false).each {
      final compile = (AbstractCompile) it
      compile.sourceCompatibility = JavaVersion.VERSION_1_8
      compile.targetCompatibility = JavaVersion.VERSION_1_8
    }

    // add csi classes to test classpath
    final testSourceSet = sourceSets.named(SourceSet.TEST_SOURCE_SET_NAME).get()
    testSourceSet.compileClasspath += csiSourceSet.output.classesDirs
    testSourceSet.runtimeClasspath += csiSourceSet.output.classesDirs
    target.dependencies.add('testImplementation', csiSourceSet.output)

    // include classes in final JAR
    (target.tasks.named('jar').get()).configure { Jar it -> it.from(csiSourceSet.output.classesDirs) }
  }

  private static void createTasks(final Project target) {
    final compileTask = (AbstractCompile) target.tasks.named('compileJava').get()
    final extension = target.extensions.getByType(CallSiteInstrumentationExtension)
    final input = compileTask.destinationDirectory
    final output = target.layout.buildDirectory.dir(extension.targetFolder)
    final targetFolder = output.get().asFile
    createGenerateCallSiteTask(target, compileTask, input, output)
    target.tasks.matching { Task task -> task.name.startsWith('compileTest') }.configureEach {
      ((AbstractCompile) it).classpath += target.files(targetFolder)
    }
    target.tasks.matching { Task task -> task instanceof Test }.configureEach {
      ((Test) it).classpath += target.files(targetFolder)
    }
  }

  private static File newBuildFolder(final Project target, final String name) {
    final folder = target.layout.buildDirectory.dir(name).get().asFile
    if (!folder.exists()) {
      if (!folder.mkdirs()) {
        throw new GradleException("Cannot create folder $folder")
      }
    }
    return folder
  }

  private static File newTempFile(final File folder, final String name) {
    final file = new File(folder, name)
    if (!file.exists() && !file.createNewFile()) {
      throw new GradleException("Cannot create temporary file: $file")
    }
    file.deleteOnExit()
    return file
  }

  private static void createGenerateCallSiteTask(final Project target,
                                                 final AbstractCompile compileTask,
                                                 final DirectoryProperty input,
                                                 final Provider<Directory> output) {
    final extension = target.extensions.getByType(CallSiteInstrumentationExtension)
    final taskName = compileTask.name.replace('compile', 'generateCallSite')
    final callSiteGeneratorTask = target.tasks.register(taskName, JavaExec).get()
    final stdout = new ByteArrayOutputStream()
    final stderr = new ByteArrayOutputStream()
    callSiteGeneratorTask.group = 'call site instrumentation'
    callSiteGeneratorTask.description = "Generates call sites from ${compileTask.name}"
    if (extension.javaVersion != null) {
      configureLanguage(target, callSiteGeneratorTask, extension.javaVersion)
    }
    callSiteGeneratorTask.setStandardOutput(stdout)
    callSiteGeneratorTask.setErrorOutput(stderr)
    callSiteGeneratorTask.inputs.dir(input)
    callSiteGeneratorTask.outputs.dir(output)
    callSiteGeneratorTask.mainClass.set('datadog.trace.plugin.csi.PluginApplication')

    final rootFolder = extension.rootFolder ?: target.rootDir
    final path = Paths.get(rootFolder.toString(),
      'buildSrc', 'call-site-instrumentation-plugin', 'build', 'libs', 'call-site-instrumentation-plugin-all.jar')
    callSiteGeneratorTask.jvmArgs(extension.jvmArgs)
    callSiteGeneratorTask.classpath(path.toFile())
    callSiteGeneratorTask.setIgnoreExitValue(true)
    // pass the arguments to the main via file to prevent issues with too long classpaths
    callSiteGeneratorTask.doFirst { JavaExec execTask ->
      final argumentFile = newTempFile(execTask.getTemporaryDir(), 'call-site-arguments')
      argumentFile.withWriter {
        it.writeLine(target.getProjectDir().toPath().resolve(extension.srcFolder).toString())
        it.writeLine(input.get().asFile.toString())
        it.writeLine(output.get().asFile.toString())
        it.writeLine(extension.suffix)
        it.writeLine(extension.reporters.join(','))
        getProgramClasspath(target).each { classpath -> it.writeLine(classpath.toString()) }
      }
      execTask.args(argumentFile.toString())
    }
    callSiteGeneratorTask.doLast { JavaExec task ->
      target.logger.info(stdout.toString())
      target.logger.error(stderr.toString())
      if (task.executionResult.get().exitValue != 0) {
        throw new GradleException("Failed to generate call site classes, check task logs for more information")
      }
    }

    // insert task after compile
    callSiteGeneratorTask.dependsOn(compileTask)
    final sourceSets = getSourceSets(target)
    final mainSourceSet = sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME).get()
    target.tasks.named(mainSourceSet.classesTaskName).configure { it.dependsOn(callSiteGeneratorTask) }

    // compile generated sources
    final csiSourceSet = sourceSets.named('csi').get()
    target.tasks.named(csiSourceSet.compileJavaTaskName).configure { callSiteGeneratorTask.finalizedBy(it) }
  }

  private static List<File> getProgramClasspath(final Project project) {
    final List<File> classpath = []
    // 1. Compilation outputs
    project.tasks.matching { Task task -> task instanceof AbstractCompile }.configureEach {
      final compileTask = (AbstractCompile) it
      classpath.add(compileTask.destinationDirectory.getAsFile().get())
    }
    // 2. Compile time dependencies
    project.tasks.matching { Task task -> task instanceof AbstractCompile }.configureEach {
      final compileTask = (AbstractCompile) it
      compileTask.classpath.every { classpath.add(it) }
    }
    // 3. Test time dependencies
    project.tasks.matching { Task task -> task instanceof Test }.configureEach {
      final testTask = (Test) it
      testTask.classpath.every { classpath.add(it) }
    }
    return classpath
  }

  @CompileDynamic
  private static SourceSetContainer getSourceSets(final Project target) {
    return target.sourceSets
  }

  @CompileDynamic
  private static void configureLanguage(final Project target, final JavaExec task, final JavaLanguageVersion version) {
    task.getJavaLauncher().set(target.javaToolchains.launcherFor {
      languageVersion = version
    })
  }
}

@CompileStatic
class CallSiteInstrumentationExtension {
  String suffix = 'CallSite'
  String srcFolder = "src${File.separatorChar}main${File.separatorChar}java"
  String targetFolder = "generated${File.separatorChar}sources${File.separatorChar}csi"
  List<String> reporters = ['CONSOLE']
  File rootFolder
  JavaLanguageVersion javaVersion
  String[] jvmArgs = ['-Xmx128m', '-Xms64m']
}
