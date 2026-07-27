package datadog.trace.instrumentation.gradle;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.SourceSetOutput;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CiVisibilityPlugin implements Plugin<Project> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilityPlugin.class);

  private static final String PLUGIN_EXTENSION_NAME = "dd-ci-visibility";
  private static final String JACOCO_PLUGIN_ID = "jacoco";
  private static final String JACOCO_AGENT_CONFIGURATION_NAME = "jacocoAgent";
  private static final String JACOCO_ANT_CONFIGURATION_NAME = "jacocoAnt";

  private Project project;

  @Override
  public void apply(Project project) {
    this.project = project;

    CiVisibilityPluginExtension extension =
        project
            .getExtensions()
            .create(PLUGIN_EXTENSION_NAME, CiVisibilityPluginExtension.class, project.getObjects());
    calculateCompiledClassesFolders(extension);
    applyCompilerPlugin(extension);
    applyJacocoPlugin(extension);
    applyToTestTasks(extension);
  }

  private void calculateCompiledClassesFolders(CiVisibilityPluginExtension extension) {
    SourceSetContainer gradleSourceSets =
        project.getExtensions().findByType(SourceSetContainer.class);
    if (gradleSourceSets == null) {
      return;
    }

    Collection<datadog.trace.api.civisibility.domain.SourceSet> sourceSets = new ArrayList<>();
    List<String> sourceSetNames = extension.getCoverageEnabledSourceSets();
    for (String sourceSetName : sourceSetNames) {
      SourceSet sourceSet = gradleSourceSets.findByName(sourceSetName);
      if (sourceSet != null) {
        datadog.trace.api.civisibility.domain.SourceSet.Type sourceSetType =
            sourceSet.getName().toLowerCase().contains("test")
                ? datadog.trace.api.civisibility.domain.SourceSet.Type.TEST
                : datadog.trace.api.civisibility.domain.SourceSet.Type.CODE;

        SourceDirectorySet allSource = sourceSet.getAllSource();
        Collection<File> srcDirs = allSource.getSrcDirs();

        SourceSetOutput output = sourceSet.getOutput();
        Collection<File> destinationDirs = output.getFiles();

        sourceSets.add(
            new datadog.trace.api.civisibility.domain.SourceSet(
                sourceSetType, srcDirs, destinationDirs));
      }
    }

    extension.setModuleLayout(new BuildModuleLayout(sourceSets));
  }

  private void applyCompilerPlugin(CiVisibilityPluginExtension extension) {
    if (extension.isCompilerPluginEnabled()) {
      addCompilerPluginConfigurations(extension);
      addModuleName(extension);
      applyToCompileTasks(extension);
    }
  }

  public void addCompilerPluginConfigurations(CiVisibilityPluginExtension extension) {
    Configuration configuration =
        project
            .getConfigurations()
            .detachedConfiguration(
                project
                    .getDependencies()
                    .create(
                        String.format(
                            "com.datadoghq:dd-javac-plugin:%s",
                            extension.getCompilerPluginVersion())),
                project
                    .getDependencies()
                    .create(
                        String.format(
                            "com.datadoghq:dd-javac-plugin-client:%s",
                            extension.getCompilerPluginVersion())));

    disableDependencyVerificationIfConfigured(configuration);

    extension.setCompilerPluginClasspath(configuration);
  }

  private void disableDependencyVerificationIfConfigured(Configuration configuration) {
    if (Config.get().isCiVisibilityGradleDependencyVerificationEnabled()) {
      return;
    }

    if (Files.isRegularFile(
        project
            .getRootProject()
            .getProjectDir()
            .toPath()
            .resolve("gradle/verification-metadata.xml"))) {
      LOGGER.warn(
          "Datadog Test Optimization disabled Gradle dependency verification for configuration "
              + "{}. To keep verification enabled, set "
              + "DD_CIVISIBILITY_GRADLE_DEPENDENCY_VERIFICATION_ENABLED=true and add "
              + "the Datadog compiler plugin and injected JaCoCo dependencies to "
              + "gradle/verification-metadata.xml. If the metadata is not updated, Gradle will "
              + "fail the build while resolving these dependencies.",
          configuration.getName());
    }
    configuration.getResolutionStrategy().disableDependencyVerification();
  }

  private void addModuleName(CiVisibilityPluginExtension extension) {
    extension.setModuleName(getModuleName());
  }

  private static final Pattern MODULE_NAME_PATTERN =
      Pattern.compile("\\s*module\\s*((\\w|\\.)+)\\s*\\{");

  private String getModuleName() {
    Path projectDir = project.getProjectDir().toPath();
    Path moduleInfo = projectDir.resolve(Paths.get("src", "main", "java", "module-info.java"));
    try {
      if (Files.exists(moduleInfo)) {
        List<String> lines = Files.readAllLines(moduleInfo);
        for (String line : lines) {
          Matcher matcher = MODULE_NAME_PATTERN.matcher(line);
          if (matcher.matches()) {
            return matcher.group(1);
          }
        }
      }
    } catch (Exception e) {
      LOGGER.error("Could not read module name from {}", moduleInfo, e);
    }
    return null;
  }

  private void applyToCompileTasks(CiVisibilityPluginExtension extension) {
    project.getTasks().withType(JavaCompile.class).configureEach(extension::applyTo);
  }

  private void applyJacocoPlugin(CiVisibilityPluginExtension extension) {
    if (!extension.isJacocoInjectionEnabled()
        || project.getPluginManager().hasPlugin(JACOCO_PLUGIN_ID)) {
      return;
    }

    project.getPluginManager().apply(JACOCO_PLUGIN_ID);
    JacocoPluginExtension jacocoExtension =
        project.getExtensions().getByType(JacocoPluginExtension.class);
    jacocoExtension.setToolVersion(extension.getJacocoVersion());

    List<Configuration> jacocoConfigurations =
        project.getConfigurations().stream()
            .filter(
                c ->
                    JACOCO_AGENT_CONFIGURATION_NAME.equals(c.getName())
                        || JACOCO_ANT_CONFIGURATION_NAME.equals(c.getName()))
            .collect(Collectors.toList());
    for (Configuration jacocoConfiguration : jacocoConfigurations) {
      disableDependencyVerificationIfConfigured(jacocoConfiguration);
    }
  }

  private void applyToTestTasks(CiVisibilityPluginExtension extension) {
    project.getTasks().withType(Test.class).configureEach(extension::applyTo);
  }
}
