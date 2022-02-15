import net.bytebuddy.ClassFileVersion
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
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.testing.Test

class InstrumentPlugin implements Plugin<Project> {

  @Override
  void apply(Project project) {
    InstrumentExtension extension = project.extensions.create('instrument', InstrumentExtension)

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
          byteBuddyTask.classFileVersion = ClassFileVersion.JAVA_V7

          byteBuddyTask.incrementalResolver = IncrementalResolver.ForChangedFiles.INSTANCE

          // use intermediate 'raw' directory for unprocessed classes
          Directory classesDir = compileTask.destinationDirectory.get()
          Directory rawClassesDir = classesDir.dir('../raw/')

          byteBuddyTask.source = rawClassesDir
          byteBuddyTask.target = classesDir

          compileTask.destinationDir = rawClassesDir.asFile

          byteBuddyTask.classPath.from((project.configurations.findByName('instrumentationMuzzle') ?: []) +
              project.configurations.compileClasspath + compileTask.destinationDir)

          byteBuddyTask.transformation {
            it.plugin = InstrumentLoader
            it.argument({ it.value = byteBuddyTask.classPath.collect({ it.toURI() as String }) })
            it.argument({ it.value = extension.plugins.get() })
            it.argument({ it.value = byteBuddyTask.target.get().asFile.path }) // must serialize as String
          }

          // insert task between compile and jar, and before test*
          byteBuddyTask.dependsOn(compileTask)
          project.tasks.named(project.sourceSets.main.classesTaskName).configure {
            dependsOn(byteBuddyTask)
          }
        }
      }
    }
  }
}

abstract class InstrumentExtension {
  abstract ListProperty<String> getPlugins()
}

class InstrumentLoader implements net.bytebuddy.build.Plugin {
  List<String> pluginClassPath

  List<String> pluginNames

  File targetDir

  net.bytebuddy.build.Plugin[] plugins

  InstrumentLoader(List<String> pluginClassPath, List<String> pluginNames, String targetDir) {
    this.pluginClassPath = pluginClassPath
    this.pluginNames = pluginNames
    this.targetDir = new File(targetDir)
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
                               ClassFileLocator classFileLocator) {
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
      } as URL[], ByteBuddyTask.classLoader)

      plugins = pluginNames.collect({
        pluginLoader.loadClass(it).getConstructor(File).newInstance(targetDir)
      }) as net.bytebuddy.build.Plugin[]
    }
    return plugins
  }
}
