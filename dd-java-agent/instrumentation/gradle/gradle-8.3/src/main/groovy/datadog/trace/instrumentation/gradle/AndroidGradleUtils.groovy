package datadog.trace.instrumentation.gradle

import datadog.trace.api.civisibility.domain.BuildModuleLayout
import datadog.trace.api.civisibility.domain.SourceSet
import kotlin.Unit
import kotlin.jvm.functions.Function1
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.util.PatternSet
import org.gradle.api.tasks.testing.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Captures Android production sources and classes during variant configuration and attaches them to
 * the exact Gradle test task. The stored layout is materialized when that task starts.
 */
class AndroidGradleUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(AndroidGradleUtils)

  private static final String MODULE_LAYOUT_EXTENSION_NAME = 'ddAndroidModuleLayout'
  private static final String ANDROID_COMPONENTS_CONFIGURED = 'ddAndroidComponentsConfigured'

  private static final ANDROID_PLUGIN_IDS = [
    'com.android.application',
    'com.android.library',
    'com.android.dynamic-feature',
    'com.android.test',
    'com.android.kotlin.multiplatform.library'
  ]

  static void configure(Project project) {
    for (String pluginId : ANDROID_PLUGIN_IDS) {
      project.pluginManager.withPlugin(pluginId, new ConfigureAndroidPluginAction(project))
    }
  }

  private static void configureAndroidComponents(Project project) {
    def extraProperties = project.extensions.extraProperties
    if (extraProperties.has(ANDROID_COMPONENTS_CONFIGURED)) {
      return
    }

    def androidComponents = project.extensions.findByName('androidComponents')
    if (androidComponents == null) {
      return
    }

    extraProperties.set(ANDROID_COMPONENTS_CONFIGURED, true)
    // AGP 8.x KMP exposes onVariant(Action), while standard Android plugins expose onVariants.
    if (supportsMethod(androidComponents, 'selector', null)) {
      androidComponents.onVariants(
        androidComponents.selector().all(), new ConfigureVariantAction(project, false))
    } else {
      androidComponents.onVariant(new ConfigureVariantAction(project, true))
    }
  }

  private static void configureVariant(
    Project project, variant, boolean useKmpCompilationSources) {
    def unitTest = getProperty(variant, 'unitTest')
    if (unitTest != null) {
      configureTestComponent(project, variant, unitTest, useKmpCompilationSources)
    }

    def hostTests = getProperty(variant, 'hostTests')
    if (hostTests instanceof Map) {
      for (def hostTest : hostTests.values()) {
        configureTestComponent(project, variant, hostTest, useKmpCompilationSources)
      }
    }
  }

  private static void configureTestComponent(
    Project project, variant, testComponent, boolean useKmpCompilationSources) {
    if (!supportsMethod(testComponent, 'configureTestTask', Function1)) {
      return
    }

    // Let AGP identify the test task instead of deriving its variant relationship from task names.
    testComponent.configureTestTask(
      new ConfigureTestTaskFunction(project, variant, useKmpCompilationSources))
  }

  private static void configureTestTask(
    Project project, variant, Test testTask, boolean useKmpCompilationSources) {
    if (testTask.extensions.findByName(MODULE_LAYOUT_EXTENSION_NAME) != null) {
      return
    }

    def layout = new AndroidModuleLayout(project)
    testTask.extensions.add(MODULE_LAYOUT_EXTENSION_NAME, layout)
    if (useKmpCompilationSources) {
      addKmpSourceDirectories(layout.sourceDirectories, project, variant)
    } else {
      addSourceDirectories(layout.sourceDirectories, variant.sources, 'java')
      addSourceDirectories(layout.sourceDirectories, variant.sources, 'kotlin')
    }

    def artifactsClassLoader = variant.artifacts.class.classLoader
    def scopeClass = artifactsClassLoader.loadClass('com.android.build.api.variant.ScopedArtifacts$Scope')
    def projectScope
    for (def scope : scopeClass.enumConstants) {
      if (scope.name() == 'PROJECT') {
        projectScope = scope
        break
      }
    }
    def classesArtifact = artifactsClassLoader
      .loadClass('com.android.build.api.artifact.ScopedArtifact$CLASSES')
      .getField('INSTANCE')
      .get(null)

    // Use the production variant's PROJECT-scoped classes to exclude dependencies and test outputs.
    def taskProvider = project.tasks.named(testTask.name, Test)
    variant.artifacts.forScope(projectScope).use(taskProvider).toGet(
      classesArtifact,
      new ClassJarsFunction(),
      new ClassDirectoriesFunction())

    testTask.inputs.files(layout.classJars, layout.classDirectories)
      .withPropertyName('ddAndroidProductionClasses')
  }

  private static void addSourceDirectories(
    ConfigurableFileCollection destination, sources, String language) {
    def languageSources = getProperty(sources, language)
    if (languageSources != null) {
      destination.from(languageSources.all)
    }
  }

  private static void addKmpSourceDirectories(
    ConfigurableFileCollection destination, Project project, variant) {
    // AGP 8.x KMP variants have no sources API. Their roots come from the matching main compilation.
    def kotlinExtension = project.extensions.findByName('kotlin')
    def targets = kotlinExtension != null ? getProperty(kotlinExtension, 'targets') : null
    if (targets == null) {
      return
    }

    def targetClass = variant.artifacts.class.classLoader
      .loadClass('com.android.build.api.dsl.KotlinMultiplatformAndroidTarget')
    for (def target : targets) {
      if (!targetClass.isInstance(target)) {
        continue
      }

      def mainCompilation = target.compilations.findByName('main')
      if (mainCompilation == null || mainCompilation.defaultSourceSet.name != variant.name) {
        continue
      }

      for (def sourceSet : mainCompilation.allKotlinSourceSets) {
        destination.from(sourceSet.kotlin.srcDirs)
      }
      break
    }
  }

  private static boolean supportsMethod(Object object, String name, Class parameterType) {
    for (def method : object.class.methods) {
      if (method.name != name) {
        continue
      }
      if (parameterType == null && method.parameterCount == 0) {
        return true
      }
      if (method.parameterCount == 1 && method.parameterTypes[0] == parameterType) {
        return true
      }
    }
    return false
  }

  private static getProperty(object, String name) {
    return object.hasProperty(name) ? object."$name" : null
  }

  static BuildModuleLayout getAndroidModuleLayout(Project project, Test task) {
    try {
      def layout = getModuleLayout(task)
      if (layout == null) {
        return null
      }

      def sources = layout.sourceDirectories.files
      def destinations = getDestinations(project, layout)
      if (sources.isEmpty() || destinations.isEmpty()) {
        return null
      }

      return new BuildModuleLayout(Collections.singletonList(new SourceSet(SourceSet.Type.CODE, sources, destinations)))
    } catch (Exception e) {
      LOGGER.error("Could not get Android module layout for ${project.name} and ${task.path}", e)
      return null
    }
  }

  private static AndroidModuleLayout getModuleLayout(Test task) {
    return task.extensions.findByName(MODULE_LAYOUT_EXTENSION_NAME) as AndroidModuleLayout
  }

  private static final EXCLUDES = [
    'android/databinding/**/*.class',
    '**/android/databinding/*Binding.class',
    '**/BR.*',
    '**/R.class',
    '**/R$*.class',
    '**/BuildConfig.*',
    '**/Manifest*.*',
    '**/*$ViewInjector*.*',
    '**/*$ViewBinder*.*',
    '**/*_MembersInjector.class',
    '**/Dagger*Component.class',
    '**/Dagger*Component$Builder.class',
    '**/*Module_*Factory.class'
  ]

  private static Collection<File> getDestinations(Project project, AndroidModuleLayout layout) {
    PatternSet classFilePatterns = new PatternSet()
    classFilePatterns.include('**/*.class')
    classFilePatterns.exclude(EXCLUDES)

    Set<File> destinations = []
    for (Directory classDirectory : layout.classDirectories.get()) {
      FileTree classFiles = project.fileTree(classDirectory.asFile)
      destinations.addAll(classFiles.matching(classFilePatterns).files)
    }
    for (RegularFile classJar : layout.classJars.get()) {
      FileTree classFiles = project.zipTree(classJar.asFile)
      destinations.addAll(classFiles.matching(classFilePatterns).files)
    }

    LOGGER.debug("Using Android class destinations: {}", destinations)
    return destinations
  }

  private static class ConfigureAndroidPluginAction implements Action<Object> {
    private final Project project

    ConfigureAndroidPluginAction(Project project) {
      this.project = project
    }

    @Override
    void execute(Object ignored) {
      try {
        configureAndroidComponents(project)
      } catch (Exception e) {
        LOGGER.error("Could not configure Android module layout for ${project.name}", e)
      }
    }
  }

  private static class ConfigureVariantAction implements Action<Object> {
    private final Project project
    private final boolean useKmpCompilationSources

    ConfigureVariantAction(Project project, boolean useKmpCompilationSources) {
      this.project = project
      this.useKmpCompilationSources = useKmpCompilationSources
    }

    @Override
    void execute(Object variant) {
      try {
        configureVariant(project, variant, useKmpCompilationSources)
      } catch (Exception e) {
        LOGGER.error("Could not configure Android variant for ${project.name}", e)
      }
    }
  }

  private static class ConfigureTestTaskFunction implements Function1<Test, Unit> {
    private final Project project
    private final Object variant
    private final boolean useKmpCompilationSources

    ConfigureTestTaskFunction(
    Project project, Object variant, boolean useKmpCompilationSources) {
      this.project = project
      this.variant = variant
      this.useKmpCompilationSources = useKmpCompilationSources
    }

    @Override
    Unit invoke(Test testTask) {
      try {
        configureTestTask(project, variant, testTask, useKmpCompilationSources)
      } catch (Exception e) {
        LOGGER.error("Could not configure Android module layout for ${testTask.path}", e)
      }
      return Unit.INSTANCE
    }
  }

  private static class ClassJarsFunction implements Function1<Test, ListProperty<RegularFile>> {
    @Override
    ListProperty<RegularFile> invoke(Test testTask) {
      return getModuleLayout(testTask).classJars
    }
  }

  private static class ClassDirectoriesFunction implements Function1<Test, ListProperty<Directory>> {
    @Override
    ListProperty<Directory> invoke(Test testTask) {
      return getModuleLayout(testTask).classDirectories
    }
  }

  private static class AndroidModuleLayout {
    final ConfigurableFileCollection sourceDirectories
    final ListProperty<RegularFile> classJars
    final ListProperty<Directory> classDirectories

    AndroidModuleLayout(Project project) {
      sourceDirectories = project.objects.fileCollection()
      classJars = project.objects.listProperty(RegularFile)
      classDirectories = project.objects.listProperty(Directory)
    }
  }
}
