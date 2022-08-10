import datadog.trace.plugin.csi.SpecificationBuilder
import datadog.trace.plugin.csi.TypeResolver
import datadog.trace.plugin.csi.impl.CallSiteSpecification
import datadog.trace.plugin.csi.impl.TypeResolverPool
import freemarker.core.Environment
import freemarker.ext.beans.StringModel
import freemarker.template.Configuration
import freemarker.template.TemplateDirectiveBody
import freemarker.template.TemplateDirectiveModel
import freemarker.template.TemplateException
import freemarker.template.TemplateMethodModelEx
import freemarker.template.TemplateModel
import freemarker.template.TemplateModelException
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.impldep.org.jetbrains.annotations.NonNls
import org.gradle.jvm.tasks.Jar

import javax.annotation.Nonnull
import java.util.stream.Collectors
import java.util.stream.Stream

import static datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult
import static datadog.trace.plugin.csi.impl.CallSiteFactory.adviceGenerator
import static datadog.trace.plugin.csi.impl.CallSiteFactory.specificationBuilder
import static groovy.io.FileType.FILES

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
    final mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
    final csiConfiguration = target.configurations.getByName(csiSourceSet.compileClasspathConfigurationName)
    final mainConfiguration = target.configurations.getByName(mainSourceSet.compileClasspathConfigurationName)
    csiConfiguration.extendsFrom(mainConfiguration)
    csiSourceSet.compileClasspath += mainSourceSet.output // mainly needed for the plugin tests
    csiSourceSet.annotationProcessorPath += mainSourceSet.annotationProcessorPath
    csiSourceSet.java.srcDir(targetFolder)

    // add csi classes to test classpath
    final testSourceSet = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME)
    testSourceSet.compileClasspath += csiSourceSet.output.classesDirs
    testSourceSet.runtimeClasspath += csiSourceSet.output.classesDirs
    target.dependencies.add('testImplementation', csiSourceSet.output)

    // include classes in final JAR
    target.tasks.named('jar').configure { Jar it -> it.from(csiSourceSet.output.classesDirs) }
  }

  private static void createTasks(final Project target) {
    final compileTask = (AbstractCompile) target.tasks.findByName('compileJava')
    final callSiteGeneratorTask = createGenerateCallSiteTask(target, compileTask)
    target.tasks.matching { Task task -> task.name.startsWith('compileTest') }.all {
      final compileTestTask = (AbstractCompile) it
      compileTestTask.classpath = compileTestTask.classpath + target.files(callSiteGeneratorTask.targetFolder)
    }
    target.tasks.matching { Task task -> task instanceof Test }.all {
      final testTask = (Test) it
      testTask.classpath = testTask.classpath + target.files(callSiteGeneratorTask.targetFolder)
    }
  }

  private static File newBuildFolder(final Project target, final String name) {
    final folder = new File(target.buildDir, name)
    if (folder.exists()) {
      folder.traverse(type: FILES) {
        if (!it.delete()) {
          throw new GradleException("Cannot delete stale file $it")
        }
      }
    } else {
      if (!folder.mkdirs()) {
        throw new GradleException("Cannot create folder $folder")
      }
    }
    return folder
  }

  private static CallSiteGeneratorTask createGenerateCallSiteTask(final Project target, final AbstractCompile compileTask) {
    final extension = target.extensions.getByType(CallSiteInstrumentationExtension)
    final input = compileTask.destinationDirectory
    final output = target.layout.buildDirectory.dir(extension.targetFolder)
    String taskName = compileTask.name.replace('compile', 'generateCallSite')
    final callSiteGeneratorTask = target.tasks.create(taskName, CallSiteGeneratorTask)
    callSiteGeneratorTask.group = 'call site instrumentation'
    callSiteGeneratorTask.description = "Generates call sites from ${compileTask.name}"
    callSiteGeneratorTask.classesFolder = input.get().asFile
    callSiteGeneratorTask.suffix = extension.suffix
    callSiteGeneratorTask.targetFolder = output.get().asFile
    callSiteGeneratorTask.reporters = extension.reporters
    callSiteGeneratorTask.inputs.dir(input)
    callSiteGeneratorTask.outputs.dir(output)

    // insert task between compile and jar, and before test*
    callSiteGeneratorTask.dependsOn(compileTask)
    final sourceSets = getSourceSets(target)
    final mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
    target.tasks.named(mainSourceSet.classesTaskName).configure { it.dependsOn(callSiteGeneratorTask) }

    final csiSourceSet = sourceSets.getByName('csi')
    target.tasks.named(csiSourceSet.compileJavaTaskName).configure { callSiteGeneratorTask.finalizedBy(it) }

    return callSiteGeneratorTask
  }

  @CompileDynamic
  private static SourceSetContainer getSourceSets(final Project target) {
    return target.sourceSets
  }
}

@CompileStatic
class CallSiteInstrumentationExtension {
  String suffix = 'CallSite'
  String targetFolder = 'generated/sources/csi'
  List<String> reporters = ['CONSOLE']
}

@CompileStatic
class CallSiteGeneratorTask extends DefaultTask {

  @InputDirectory
  File classesFolder

  @Input
  String suffix

  @OutputDirectory
  File targetFolder

  @Input
  List<String> reporters

  @TaskAction
  void generateCallSites() {
    cleanUpTargetFolder()
    final typeResolver = buildTypeResolver()
    final specs = searchForCallSites(specificationBuilder())
    final generator = adviceGenerator(targetFolder, typeResolver)
    final result = specs.collect { generator.generate(it) }
    boolean failed = result.any { !it.success }
    printReport(failed, result)
    if (failed) {
      throw new GradleException("Failed to generate call site classes, check task logs for more information")
    }
  }

  private void cleanUpTargetFolder() {
    targetFolder.traverse(type: FILES) {
      if (!it.delete()) {
        throw new GradleException("Cannot delete stale file $it")
      }
    }
  }

  private List<CallSiteSpecification> searchForCallSites(final SpecificationBuilder builder) {
    final List<CallSiteSpecification> specs = []
    classesFolder.traverse(type: FILES, nameFilter: ~/.*$suffix\.class$/) {
      builder.build(it).ifPresent(spec -> specs.add(spec))
    }
    return specs
  }

  private void printReport(final boolean failed, final List<CallSiteResult> results) {
    reporters.collect { CallSiteReporter.REPORTERS[it] }.each { it.report(this, failed, results) }
  }

  private TypeResolver buildTypeResolver() {
    final Set<URL> classpath = new HashSet<>()
    project.tasks.matching { Task task -> task instanceof AbstractCompile }.all {
      final compileTask = (AbstractCompile) it
      compileTask.classpath.every { classpath.add(it.toURI().toURL()) }
      classpath.add(compileTask.destinationDirectory.getAsFile().get().toURI().toURL())
    }
    project.tasks.matching { Task task -> task instanceof Test }.all {
      final testTask = (Test) it
      testTask.classpath.every { classpath.add(it.toURI().toURL()) }
    }
    return new TypeResolverPool(new URLClassLoader(classpath.toArray() as URL[], getClass().classLoader))
  }
}

@CompileStatic
interface CallSiteReporter {
  Map<String, CallSiteReporter> REPORTERS = [
    CONSOLE: new ConsoleReporter()
  ] as Map<String, CallSiteReporter>

  void report(CallSiteGeneratorTask task, boolean error, List<CallSiteResult> results)

  abstract class FreemarkerReporter implements CallSiteReporter {
    final String template

    FreemarkerReporter(final String template) {
      this.template = template
    }

    protected void write(final List<CallSiteResult> results, final Writer writer) {
      final cfg = new Configuration(Configuration.VERSION_2_3_30)
      cfg.setClassLoaderForTemplateLoading(Thread.currentThread().contextClassLoader, 'csi')
      cfg.setDefaultEncoding("UTF-8")
      final input = [
        results: results,
        toList: new ToListDirective()
      ]
      final template = cfg.getTemplate('console.ftl')
      template.process(input, writer)
    }

    private static class ToListDirective implements TemplateMethodModelEx {

      @Override
      Object exec(final List arguments) throws TemplateModelException {
        final model = arguments.get(0) as StringModel
        final stream = model.wrappedObject as Stream<?>
        return stream.collect(Collectors.toList())
      }
    }
  }

  class ConsoleReporter extends FreemarkerReporter {

    ConsoleReporter() {
      super('console.ftl')
    }

    @Override
    void report(final CallSiteGeneratorTask task, final boolean failed, final List<CallSiteResult> results) {
      final output = new StringWriter().withWriter {
        write(results, it)
        it.toString()
      }
      final logger = failed ? task.logger.&error : task.logger.&info
      logger.call(output)
    }
  }
}




