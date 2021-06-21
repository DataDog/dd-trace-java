import net.bytebuddy.build.EntryPoint
import net.bytebuddy.build.gradle.ByteBuddyTask
import net.bytebuddy.build.gradle.Discovery
import net.bytebuddy.build.gradle.IncrementalResolver
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.tasks.compile.AbstractCompile

class InstrumentPlugin implements Plugin<Project> {

  @Override
  void apply(Project project) {
    Project toolingProject = project.project(':dd-java-agent:agent-tooling')

    project.tasks.matching { it.name in ['compileJava', 'compileScala', 'compileKotlin'] }.all {
      AbstractCompile compileTask = it as AbstractCompile
      project.afterEvaluate {
        if (!compileTask.source.empty) {
          String instrumentName = compileTask.name.replace('compile', 'instrument')

          ByteBuddyTask byteBuddyTask = project.tasks.create(instrumentName, ByteBuddyTask)
          byteBuddyTask.group = 'Byte Buddy'
          byteBuddyTask.description = "Instruments the classes compiled by ${compileTask.name}"

          byteBuddyTask.entryPoint = EntryPoint.Default.REBASE
          byteBuddyTask.suffix = ''
          byteBuddyTask.failOnLiveInitializer = true
          byteBuddyTask.warnOnEmptyTypeSet = true
          byteBuddyTask.failFast = false
          byteBuddyTask.extendedParsing = false
          byteBuddyTask.discovery = Discovery.NONE
          byteBuddyTask.threads = 0

          byteBuddyTask.incrementalResolver = IncrementalResolver.ForChangedFiles.INSTANCE

          // use intermediate 'raw' directory for unprocessed classes
          Directory classesDir = compileTask.destinationDirectory.get()
          Directory rawClassesDir = classesDir.dir('../raw/')

          byteBuddyTask.source = rawClassesDir
          byteBuddyTask.target = classesDir

          compileTask.destinationDir = rawClassesDir.asFile

          byteBuddyTask.classPath.from(toolingProject.configurations.instrumentationMuzzle +
              project.configurations.compileClasspath + compileTask.destinationDir)

          byteBuddyTask.transformation {
            it.plugin = InstrumentLoader
            it.argument({ it.value = byteBuddyTask.classPath.collect({ it.toURI() as String }) })
          }

          // insert task between compile and jar
          byteBuddyTask.dependsOn(compileTask)
          project.tasks.findByName('jar')?.dependsOn(byteBuddyTask)
        }
      }
    }
  }
}

class InstrumentLoader implements net.bytebuddy.build.Plugin {
  List<String> pluginClassPath

  String[] pluginNames = ['datadog.trace.agent.tooling.muzzle.MuzzleGradlePlugin',
                          'datadog.trace.agent.tooling.bytebuddy.NewTaskForGradlePlugin']

  net.bytebuddy.build.Plugin[] plugins

  InstrumentLoader(List<String> pluginClassPath) {
    this.pluginClassPath = pluginClassPath
  }

  @Override
  boolean matches(TypeDescription target) {
    for (net.bytebuddy.build.Plugin plugin : plugins()) {
      if (plugin.matches(target)) {
        return true
      }
    }
    return false
  }

  @Override
  DynamicType.Builder<?> apply(DynamicType.Builder<?> builder,
                               TypeDescription typeDescription,
                               ClassFileLocator classFileLocator)
  {
    for (net.bytebuddy.build.Plugin plugin : plugins()) {
      if (plugin.matches(typeDescription)) {
        builder = plugin.apply(builder, typeDescription, classFileLocator)
      }
    }
    return builder
  }

  @Override
  void close() throws IOException {
    for (net.bytebuddy.build.Plugin plugin : plugins()) {
      plugin.close()
    }
  }

  private net.bytebuddy.build.Plugin[] plugins() {
    if (null == plugins) {
      URLClassLoader pluginLoader = new URLClassLoader(pluginClassPath.collect {
        // File.toURI() will remove trailing slashes if the directory does not exist yet,
        // we need to add these slashes back for URLClassLoader to load classes from them
        return new URL(it.endsWith('/') || it.endsWith('.jar') ? it : it + '/')
      } as URL[], ByteBuddyTask.class.classLoader)

      plugins = pluginNames.collect({ pluginLoader.loadClass(it).newInstance() }) as net.bytebuddy.build.Plugin[]
    }
    return plugins
  }
}
