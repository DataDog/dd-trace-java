import net.bytebuddy.ClassFileVersion
import net.bytebuddy.build.EntryPoint
import net.bytebuddy.build.gradle.ByteBuddyTask
import net.bytebuddy.build.gradle.Discovery
import net.bytebuddy.build.gradle.IncrementalResolver
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.DynamicType
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.compile.AbstractCompile

import java.util.regex.Matcher

class InstrumentPlugin implements Plugin<Project> {

  @Override
  void apply(Project project) {
    InstrumentExtension extension = project.extensions.create('instrument', InstrumentExtension)

    project.tasks.matching {
      it.name in ['compileJava', 'compileScala', 'compileKotlin'] ||
        it.name =~ /compileMain_java(?:\d+)Java/
    }.all {
      AbstractCompile compileTask = it as AbstractCompile
      Matcher versionMatcher = it.name =~ /compileMain_java(\d+)Java/
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

          String javaVersion
          if (versionMatcher.matches()) {
            javaVersion = versionMatcher.group(1)
          }
          if (javaVersion) {
            byteBuddyTask.classFileVersion = ClassFileVersion."JAVA_V${javaVersion}"
          } else {
            byteBuddyTask.classFileVersion = ClassFileVersion.JAVA_V7
          }

          byteBuddyTask.incrementalResolver = IncrementalResolver.ForChangedFiles.INSTANCE

          // use intermediate 'raw' directory for unprocessed classes
          Directory classesDir = compileTask.destinationDirectory.get()
          Directory rawClassesDir = classesDir.dir(
            "../raw${javaVersion ? "_java${javaVersion}" : ''}/")

          byteBuddyTask.source = rawClassesDir
          byteBuddyTask.target = classesDir

          compileTask.destinationDirectory = rawClassesDir.asFile

          byteBuddyTask.classPath.from((project.configurations.findByName('instrumentationMuzzle') ?: []) +
            project.configurations.compileClasspath.findAll { it.name != 'previous-compilation-data.bin' } +
            compileTask.destinationDirectory)

          byteBuddyTask.transformation {
            it.plugin = InstrumentLoader
            it.argument({ it.value = byteBuddyTask.classPath.collect({ it.toURI() as String }) })
            it.argument({ it.value = extension.plugins.get() })
            it.argument({ it.value = byteBuddyTask.target.get().asFile.path }) // must serialize as String
          }

          // insert task between compile and jar, and before test*
          byteBuddyTask.inputs.dir(compileTask.destinationDirectory)

          if (javaVersion) {
            // the main classes depend on versioned classes (see java_no_deps.gradle)
            project.tasks.findAll { it.name =~ /\A(compile|instrument)(Java|Groovy|Scala)\z/}.each {
              it.inputs.dir(byteBuddyTask.target)
            }

            // avoid warning saying it depends on resources task
            def processTask = project.tasks[
              instrumentName.replace('instrument', 'process').replace('Java', 'Resources')]
            byteBuddyTask.dependsOn(processTask)

            it.tasks.named(project.sourceSets."main_java${javaVersion}".classesTaskName) { DefaultTask task ->
              task.dependsOn(byteBuddyTask)
            }
          } else {
            it.tasks.named(project.sourceSets.main.classesTaskName) { DefaultTask task ->
              task.dependsOn(byteBuddyTask)
            }
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
