package datadog.trace.instrumentation.gradle;

import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.compile.CompileOptions;
import org.gradle.api.tasks.compile.ForkOptions;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.jvm.Jvm;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.CommandLineArgumentProvider;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CiVisibilityPluginExtension {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilityPluginExtension.class);

  public static final String MODULE_LAYOUT_PROPERTY = "moduleLayout";

  private final ObjectFactory objectFactory;
  private FileCollection compilerPluginClasspath;
  private String moduleName;
  private BuildModuleLayout moduleLayout;

  @Inject
  public CiVisibilityPluginExtension(ObjectFactory objectFactory) {
    this.objectFactory = objectFactory;
  }

  public void setCompilerPluginClasspath(FileCollection compilerPluginClasspath) {
    this.compilerPluginClasspath = compilerPluginClasspath;
  }

  public void setModuleName(String moduleName) {
    this.moduleName = moduleName;
  }

  public void setModuleLayout(BuildModuleLayout moduleLayout) {
    this.moduleLayout = moduleLayout;
  }

  @ServiceReference
  abstract Property<CiVisibilityService> getCiVisibilityService();

  public boolean isCompilerPluginEnabled() {
    return getCiVisibilityService().get().isCompilerPluginEnabled();
  }

  public String getCompilerPluginVersion() {
    return getCiVisibilityService().get().getCompilerPluginVersion();
  }

  public boolean isJacocoInjectionEnabled() {
    CiVisibilityService ciVisibilityService = getCiVisibilityService().get();
    return ciVisibilityService.isJacocoInjectionEnabled();
  }

  public List<String> getCoverageEnabledSourceSets() {
    return getCiVisibilityService().get().getCoverageEnabledSourceSets();
  }

  public String getJacocoVersion() {
    return getCiVisibilityService().get().getJacocoVersion();
  }

  public void applyTo(JavaCompile javaCompile) {
    CompileOptions options = javaCompile.getOptions();
    ForkOptions forkOptions = options.getForkOptions();
    String compilerExecutable = forkOptions.getExecutable();
    if (compilerExecutable != null && !compilerExecutable.isEmpty()) {
      // assuming a non-standard compiler is used
      return;
    }

    FileCollection classpath = javaCompile.getClasspath();
    FileCollection updatedClasspath =
        classpath != null ? classpath.plus(compilerPluginClasspath) : compilerPluginClasspath;
    javaCompile.setClasspath(updatedClasspath);

    FileCollection annotationProcessorPath = options.getAnnotationProcessorPath();
    FileCollection updatedAnnotationProcessorPath =
        annotationProcessorPath != null
            ? annotationProcessorPath.plus(compilerPluginClasspath)
            : compilerPluginClasspath;
    options.setAnnotationProcessorPath(updatedAnnotationProcessorPath);

    CommandLineArgumentProvider argumentProvider =
        new JavaCompilerPluginArgumentsProvider(moduleName);
    options.getCompilerArgumentProviders().add(argumentProvider);
  }

  public void applyTo(Test task) {
    task.getInputs().property(MODULE_LAYOUT_PROPERTY, moduleLayout);

    applyTracerSettings(task.getPath(), getProjectProperties(task), task.getJvmArgumentProviders());

    JacocoTaskExtension jacocoTaskExtension =
        task.getExtensions().findByType(JacocoTaskExtension.class);
    if (jacocoTaskExtension != null) {
      applyJacocoSettings(jacocoTaskExtension);
    }
  }

  public static Path getEffectiveExecutable(Test task) {
    Property<JavaLauncher> javaLauncher = task.getJavaLauncher();
    if (javaLauncher.isPresent()) {
      try {
        return javaLauncher.get().getExecutablePath().getAsFile().toPath();
      } catch (Exception e) {
        LOGGER.error("Could not get Java launcher for test task", e);
      }
    }
    String executable = task.getExecutable();
    if (executable != null && !executable.isEmpty()) {
      return Paths.get(executable);
    } else {
      return Jvm.current().getJavaExecutable().toPath();
    }
  }

  public static List<Path> getClasspath(Test task) {
    try {
      return task.getClasspath().getFiles().stream().map(File::toPath).collect(Collectors.toList());
    } catch (Exception e) {
      LOGGER.error("Could not get classpath for test task", e);
      return null;
    }
  }

  private static Map<String, String> getProjectProperties(Test task) {
    Map<String, String> projectProperties = new HashMap<>();
    for (Map.Entry<String, ?> e : task.getProject().getProperties().entrySet()) {
      String propertyName = e.getKey();
      Object propertyValue = e.getValue();
      if (propertyValue instanceof String) {
        projectProperties.put(propertyName, (String) propertyValue);
      }
    }
    return projectProperties;
  }

  private void applyJacocoSettings(JacocoTaskExtension jacocoTaskExtension) {
    CiVisibilityService ciVisibilityService = getCiVisibilityService().get();

    List<String> taskExcludeClassLoaders = jacocoTaskExtension.getExcludeClassLoaders();
    if (taskExcludeClassLoaders != null) {
      // taskExcludeClassLoaders list may be immutable, so we need to construct a new one
      List<String> ciVisExcludedClassLoaders = ciVisibilityService.getExcludeClassLoaders();
      List<String> updatedTaskExcludeClassLoaders =
          new ArrayList<>(taskExcludeClassLoaders.size() + ciVisExcludedClassLoaders.size());
      updatedTaskExcludeClassLoaders.addAll(taskExcludeClassLoaders);
      updatedTaskExcludeClassLoaders.addAll(ciVisExcludedClassLoaders);
      jacocoTaskExtension.setExcludeClassLoaders(updatedTaskExcludeClassLoaders);
    } else {
      jacocoTaskExtension.setExcludeClassLoaders(
          new ArrayList<>(ciVisibilityService.getExcludeClassLoaders()));
    }

    jacocoTaskExtension.setIncludes(
        merge(
            jacocoTaskExtension.getIncludes(), ciVisibilityService.getCoverageIncludedPackages()));
    jacocoTaskExtension.setExcludes(
        merge(
            jacocoTaskExtension.getExcludes(), ciVisibilityService.getCoverageExcludedPackages()));
  }

  @SafeVarargs
  private static List<String> merge(List<String>... packageLists) {
    List<String> merged = new ArrayList<>();
    for (List<String> packageList : packageLists) {
      if (packageList == null) {
        continue;
      }
      for (String pkg : packageList) {
        if (pkg != null && !pkg.isEmpty()) {
          merged.add(pkg);
        }
      }
    }
    return merged;
  }

  private void applyTracerSettings(
      String taskPath,
      Map<String, String> projectProperties,
      List<CommandLineArgumentProvider> jvmArgumentProviders) {
    CommandLineArgumentProvider tracerArgumentsProvider =
        objectFactory.newInstance(TracerArgumentsProvider.class, taskPath, projectProperties);
    jvmArgumentProviders.add(tracerArgumentsProvider);
  }
}
