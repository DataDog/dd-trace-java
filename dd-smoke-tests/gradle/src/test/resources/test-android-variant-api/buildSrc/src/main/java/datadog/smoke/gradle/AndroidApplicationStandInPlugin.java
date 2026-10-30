package datadog.smoke.gradle;

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.variant.ScopedArtifacts;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.testing.Test;

public class AndroidApplicationStandInPlugin implements Plugin<Project> {

  @Override
  public void apply(Project project) {
    project.getPluginManager().apply("com.android.base");
    AndroidComponentsStandIn androidComponents = new AndroidComponentsStandIn(project);
    project.getExtensions().add("androidComponents", androidComponents);
    project.afterEvaluate(
        ignored -> {
          if (!androidComponents.isConfigured()) {
            throw new GradleException(
                "Android Variant API callback was not configured: onVariants="
                    + androidComponents.isVariantActionRegistered()
                    + ", configureTestTask="
                    + androidComponents.isTestTaskConfigured());
          }
        });
  }

  public static class AndroidComponentsStandIn {
    private final VariantStandIn variant;
    private boolean variantActionRegistered;

    public AndroidComponentsStandIn(Project project) {
      variant = new VariantStandIn(project);
    }

    public SelectorStandIn selector() {
      return new SelectorStandIn();
    }

    public void onVariants(Object selector, Action<Object> action) {
      variantActionRegistered = true;
      action.execute(variant);
    }

    public boolean isConfigured() {
      return isVariantActionRegistered() && isTestTaskConfigured();
    }

    public boolean isVariantActionRegistered() {
      return variantActionRegistered;
    }

    public boolean isTestTaskConfigured() {
      return variant.getUnitTest().isConfigured();
    }
  }

  public static class SelectorStandIn {
    public Object all() {
      return this;
    }
  }

  public static class VariantStandIn {
    private final SourcesStandIn sources;
    private final UnitTestStandIn unitTest;
    private final ArtifactsStandIn artifacts;

    public VariantStandIn(Project project) {
      SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
      TaskProvider<Test> testTask = project.getTasks().named("test", Test.class);
      artifacts = new ArtifactsStandIn(project);
      sources = new SourcesStandIn(project, sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME));
      unitTest = new UnitTestStandIn(testTask, artifacts);
    }

    public SourcesStandIn getSources() {
      return sources;
    }

    public UnitTestStandIn getUnitTest() {
      return unitTest;
    }

    public ArtifactsStandIn getArtifacts() {
      return artifacts;
    }
  }

  public static class SourcesStandIn {
    private final SourceDirectoriesStandIn java;

    public SourcesStandIn(Project project, SourceSet sourceSet) {
      java = new SourceDirectoriesStandIn(project, sourceSet.getJava().getSrcDirs());
    }

    public SourceDirectoriesStandIn getJava() {
      return java;
    }

    public Object getKotlin() {
      return null;
    }
  }

  public static class SourceDirectoriesStandIn {
    private final Provider<Collection<Directory>> all;

    public SourceDirectoriesStandIn(Project project, Collection<File> sourceDirectories) {
      all =
          project.provider(
              () -> {
                Collection<Directory> directories = new java.util.ArrayList<>();
                for (File sourceDirectory : sourceDirectories) {
                  directories.add(
                      project.getLayout().dir(project.provider(() -> sourceDirectory)).get());
                }
                return directories;
              });
    }

    public Provider<Collection<Directory>> getAll() {
      return all;
    }
  }

  public static class UnitTestStandIn {
    private final TaskProvider<Test> testTask;
    private final ArtifactsStandIn artifacts;
    private boolean configured;

    public UnitTestStandIn(TaskProvider<Test> testTask, ArtifactsStandIn artifacts) {
      this.testTask = testTask;
      this.artifacts = artifacts;
      testTask.configure(
          task ->
              task.doFirst(
                  ignored -> {
                    if (!artifacts.isConfigured()) {
                      throw new GradleException("Project-scoped classes were not configured");
                    }
                  }));
    }

    public void configureTestTask(Function1<Test, Unit> action) {
      configured = true;
      testTask.configure(
          task -> {
            artifacts.setTestTask(task);
            action.invoke(task);
          });
    }

    public boolean isConfigured() {
      return configured;
    }
  }

  public static class ArtifactsStandIn {
    private final Project project;
    private Test testTask;
    private boolean configured;

    public ArtifactsStandIn(Project project) {
      this.project = project;
    }

    public void setTestTask(Test testTask) {
      this.testTask = testTask;
    }

    public ArtifactsStandIn forScope(Object scope) {
      if (scope != ScopedArtifacts.Scope.PROJECT) {
        throw new GradleException("Expected project-scoped Android classes");
      }
      return this;
    }

    public ArtifactsStandIn use(TaskProvider<Test> taskProvider) {
      return this;
    }

    public void toGet(
        Object artifact,
        Function1<Test, ListProperty<RegularFile>> jars,
        Function1<Test, ListProperty<Directory>> directories) {
      if (artifact != ScopedArtifact.CLASSES.INSTANCE) {
        throw new GradleException("Expected the Android classes artifact");
      }
      configured = true;
      TaskProvider<Jar> jarTask = project.getTasks().named("jar", Jar.class);
      jars.invoke(testTask).add(jarTask.flatMap(Jar::getArchiveFile));
      directories.invoke(testTask).set(Collections.emptyList());
    }

    public boolean isConfigured() {
      return configured;
    }
  }
}
