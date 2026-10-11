package datadog.trace.instrumentation.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.dsl.KotlinMultiplatformAndroidTarget;
import com.android.build.api.variant.ScopedArtifacts;
import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.SourceSet;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;

class AndroidGradleUtilsTest {

  @TempDir Path projectDir;

  @org.junit.jupiter.api.Test
  void usesSourcesAndClassesFromTestedVariant() throws IOException {
    Project project = createProject();
    Test testTask = (Test) project.getTasks().getByName("test");

    File javaSources = createDirectory("src/internalTest/java");
    File kotlinSources = createDirectory("src/internalTest/kotlin");
    File classDirectory = createDirectory("build/classes/internalTest");
    createFile(classDirectory, "datadog/smoke/Production.class");
    createFile(classDirectory, "datadog/smoke/BuildConfig.class");
    createFile(classDirectory, "config/application.properties");

    File classJar = projectDir.resolve("build/libs/internalTest.jar").toFile();
    Map<String, byte[]> classJarEntries = new LinkedHashMap<>();
    classJarEntries.put("datadog/smoke/JarProduction.class", new byte[] {0});
    classJarEntries.put("datadog/smoke/R.class", new byte[] {0});
    classJarEntries.put("META-INF/application.properties", new byte[] {0});
    createJar(classJar, classJarEntries);

    File testFixturesJar = projectDir.resolve("build/libs/test-fixtures.jar").toFile();
    createJar(
        testFixturesJar,
        Collections.singletonMap("datadog/smoke/TestFixtureSupport.class", new byte[] {0}));
    File copiedDependencyJar = projectDir.resolve("build/copied/dependency.jar").toFile();
    createJar(
        copiedDependencyJar, Collections.singletonMap("dependency/External.class", new byte[] {0}));
    testTask.setClasspath(project.files(testFixturesJar, copiedDependencyJar));

    FakeVariant variant =
        new FakeVariant(
            project,
            testTask,
            Arrays.asList(javaSources),
            Arrays.asList(kotlinSources),
            Collections.singletonList(classJar),
            Collections.singletonList(classDirectory));
    project
        .getExtensions()
        .add("androidComponents", new FakeAndroidComponents(Collections.singletonList(variant)));

    AndroidGradleUtils.configure(project);
    project.getPluginManager().apply("com.android.application");
    BuildModuleLayout layout = AndroidGradleUtils.getAndroidModuleLayout(project, testTask);

    assertNotNull(layout);
    SourceSet sourceSet = layout.getSourceSets().iterator().next();
    assertEquals(setOf(javaSources, kotlinSources), new HashSet<>(sourceSet.getSources()));
    assertEquals(
        setOf("Production.class", "JarProduction.class"), fileNames(sourceSet.getDestinations()));
  }

  @org.junit.jupiter.api.Test
  void doesNotInferLayoutFromTestClasspath() throws IOException {
    Project project = createProject();
    Test testTask = (Test) project.getTasks().getByName("test");
    File projectLocalJar = projectDir.resolve("build/libs/production-looking.jar").toFile();
    createJar(
        projectLocalJar,
        Collections.singletonMap("datadog/smoke/Production.class", new byte[] {0}));
    testTask.setClasspath(project.files(projectLocalJar));

    assertNull(AndroidGradleUtils.getAndroidModuleLayout(project, testTask));
  }

  @org.junit.jupiter.api.Test
  void ignoresTestComponentsWithoutTaskConfigurationApi() {
    Project project = createProject();
    Test testTask = (Test) project.getTasks().getByName("test");
    project
        .getExtensions()
        .add(
            "androidComponents",
            new FakeAndroidComponents(Collections.singletonList(new FakeLegacyVariant())));

    AndroidGradleUtils.configure(project);
    project.getPluginManager().apply("com.android.application");

    assertNull(AndroidGradleUtils.getAndroidModuleLayout(project, testTask));
  }

  @org.junit.jupiter.api.Test
  void usesKmp8VariantCallbackAndProductionCompilationSources() throws IOException {
    Project project = createProject();
    Test testTask = (Test) project.getTasks().getByName("test");
    File kotlinSources = createDirectory("src/androidMain/kotlin");
    File classDirectory = createDirectory("build/classes/androidMain");
    createFile(classDirectory, "datadog/smoke/Production.class");

    FakeKmpVariant variant =
        new FakeKmpVariant(
            project, testTask, Collections.emptyList(), Collections.singletonList(classDirectory));
    project.getExtensions().add("androidComponents", new FakeKmpAndroidComponents(variant));
    project
        .getExtensions()
        .add(
            "kotlin",
            new FakeKotlinExtension(Collections.singletonList(new FakeKmpTarget(kotlinSources))));

    AndroidGradleUtils.configure(project);
    project.getPluginManager().apply("com.android.kotlin.multiplatform.library");
    BuildModuleLayout layout = AndroidGradleUtils.getAndroidModuleLayout(project, testTask);

    assertNotNull(layout);
    SourceSet sourceSet = layout.getSourceSets().iterator().next();
    assertEquals(Collections.singleton(kotlinSources), new HashSet<>(sourceSet.getSources()));
    assertEquals(Collections.singleton("Production.class"), fileNames(sourceSet.getDestinations()));
  }

  private Project createProject() {
    Project project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
    project.getPluginManager().apply("java");
    return project;
  }

  private File createDirectory(String relativePath) throws IOException {
    Path directory = projectDir.resolve(relativePath);
    Files.createDirectories(directory);
    return directory.toFile();
  }

  private static void createFile(File root, String path) throws IOException {
    Path file = root.toPath().resolve(path);
    Files.createDirectories(file.getParent());
    Files.write(file, new byte[] {0});
  }

  private static void createJar(File jar, Map<String, byte[]> entries) throws IOException {
    Files.createDirectories(jar.toPath().getParent());
    try (JarOutputStream output = new JarOutputStream(new FileOutputStream(jar))) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        output.putNextEntry(new JarEntry(entry.getKey()));
        output.write(entry.getValue());
        output.closeEntry();
      }
    }
  }

  private static Set<String> fileNames(Collection<File> files) {
    Set<String> names = new HashSet<>();
    for (File file : files) {
      names.add(file.getName());
    }
    return names;
  }

  @SafeVarargs
  private static <T> Set<T> setOf(T... values) {
    return new HashSet<>(Arrays.asList(values));
  }

  private static final class FakeAndroidComponents {
    private final Collection<?> variants;

    private FakeAndroidComponents(Collection<?> variants) {
      this.variants = variants;
    }

    public FakeSelector selector() {
      return new FakeSelector();
    }

    public void onVariants(Object selector, Action<Object> action) {
      for (Object variant : variants) {
        action.execute(variant);
      }
    }
  }

  private static final class FakeKmpAndroidComponents {
    private final Object variant;

    private FakeKmpAndroidComponents(Object variant) {
      this.variant = variant;
    }

    public void onVariant(Action<Object> action) {
      action.execute(variant);
    }
  }

  private static final class FakeSelector {
    public Object all() {
      return this;
    }
  }

  private static final class FakeLegacyVariant {

    public Object getUnitTest() {
      return new Object();
    }

    public Map<String, Object> getHostTests() {
      return Collections.emptyMap();
    }
  }

  private static final class FakeVariant {
    private final FakeSources sources;
    private final FakeTestComponent unitTest;
    private final Map<String, FakeTestComponent> hostTests;
    private final FakeArtifacts artifacts;

    private FakeVariant(
        Project project,
        Test testTask,
        Collection<File> javaSources,
        Collection<File> kotlinSources,
        Collection<File> classJars,
        Collection<File> classDirectories) {
      sources = new FakeSources(project, javaSources, kotlinSources);
      unitTest = new FakeTestComponent(testTask);
      hostTests = Collections.emptyMap();
      artifacts = new FakeArtifacts(project, testTask, classJars, classDirectories);
    }

    public FakeSources getSources() {
      return sources;
    }

    public FakeTestComponent getUnitTest() {
      return unitTest;
    }

    public Map<String, FakeTestComponent> getHostTests() {
      return hostTests;
    }

    public FakeArtifacts getArtifacts() {
      return artifacts;
    }
  }

  private static final class FakeKmpVariant {
    private final Map<String, FakeTestComponent> hostTests;
    private final FakeArtifacts artifacts;

    private FakeKmpVariant(
        Project project,
        Test testTask,
        Collection<File> classJars,
        Collection<File> classDirectories) {
      hostTests = Collections.singletonMap("testOnJvm", new FakeTestComponent(testTask));
      artifacts = new FakeArtifacts(project, testTask, classJars, classDirectories);
    }

    public String getName() {
      return "androidMain";
    }

    public Object getUnitTest() {
      return null;
    }

    public Map<String, FakeTestComponent> getHostTests() {
      return hostTests;
    }

    public FakeArtifacts getArtifacts() {
      return artifacts;
    }
  }

  private static final class FakeKotlinExtension {
    private final Collection<Object> targets;

    private FakeKotlinExtension(Collection<Object> targets) {
      this.targets = targets;
    }

    public Collection<Object> getTargets() {
      return targets;
    }
  }

  private static final class FakeKmpTarget implements KotlinMultiplatformAndroidTarget {
    private final FakeKmpCompilations compilations;

    private FakeKmpTarget(File sourceDirectory) {
      compilations = new FakeKmpCompilations(sourceDirectory);
    }

    public FakeKmpCompilations getCompilations() {
      return compilations;
    }
  }

  private static final class FakeKmpCompilations {
    private final FakeKmpCompilation main;

    private FakeKmpCompilations(File sourceDirectory) {
      main = new FakeKmpCompilation(sourceDirectory);
    }

    public FakeKmpCompilation findByName(String name) {
      return "main".equals(name) ? main : null;
    }
  }

  private static final class FakeKmpCompilation {
    private final FakeKmpSourceSet defaultSourceSet;
    private final Collection<FakeKmpSourceSet> allKotlinSourceSets;

    private FakeKmpCompilation(File sourceDirectory) {
      defaultSourceSet = new FakeKmpSourceSet("androidMain", sourceDirectory);
      allKotlinSourceSets = Collections.singleton(defaultSourceSet);
    }

    public FakeKmpSourceSet getDefaultSourceSet() {
      return defaultSourceSet;
    }

    public Collection<FakeKmpSourceSet> getAllKotlinSourceSets() {
      return allKotlinSourceSets;
    }
  }

  private static final class FakeKmpSourceSet {
    private final String name;
    private final FakeKmpSourceDirectories kotlin;

    private FakeKmpSourceSet(String name, File sourceDirectory) {
      this.name = name;
      kotlin = new FakeKmpSourceDirectories(sourceDirectory);
    }

    public String getName() {
      return name;
    }

    public FakeKmpSourceDirectories getKotlin() {
      return kotlin;
    }
  }

  private static final class FakeKmpSourceDirectories {
    private final Set<File> srcDirs;

    private FakeKmpSourceDirectories(File sourceDirectory) {
      srcDirs = Collections.singleton(sourceDirectory);
    }

    public Set<File> getSrcDirs() {
      return srcDirs;
    }
  }

  private static final class FakeSources {
    private final FakeSourceDirectories java;
    private final FakeSourceDirectories kotlin;

    private FakeSources(
        Project project, Collection<File> javaSources, Collection<File> kotlinSources) {
      java = new FakeSourceDirectories(project, javaSources);
      kotlin = new FakeSourceDirectories(project, kotlinSources);
    }

    public FakeSourceDirectories getJava() {
      return java;
    }

    public FakeSourceDirectories getKotlin() {
      return kotlin;
    }
  }

  private static final class FakeSourceDirectories {
    private final Provider<Collection<Directory>> all;

    private FakeSourceDirectories(Project project, Collection<File> sourceDirectories) {
      all =
          project.provider(
              () -> {
                Collection<Directory> directories = new HashSet<>();
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

  private static final class FakeTestComponent {
    private final Test testTask;

    private FakeTestComponent(Test testTask) {
      this.testTask = testTask;
    }

    public void configureTestTask(Function1<Test, Unit> action) {
      action.invoke(testTask);
    }
  }

  private static final class FakeArtifacts {
    private final Project project;
    private final Test testTask;
    private final Collection<File> classJars;
    private final Collection<File> classDirectories;

    private FakeArtifacts(
        Project project,
        Test testTask,
        Collection<File> classJars,
        Collection<File> classDirectories) {
      this.project = project;
      this.testTask = testTask;
      this.classJars = classJars;
      this.classDirectories = classDirectories;
    }

    public FakeArtifacts forScope(Object scope) {
      assertSame(ScopedArtifacts.Scope.PROJECT, scope);
      return this;
    }

    public FakeArtifacts use(TaskProvider<Test> taskProvider) {
      return this;
    }

    public void toGet(
        Object artifact,
        Function1<Test, ListProperty<RegularFile>> jars,
        Function1<Test, ListProperty<Directory>> directories) {
      assertSame(ScopedArtifact.CLASSES.INSTANCE, artifact);
      for (File classJar : classJars) {
        jars.invoke(testTask).add(project.getLayout().file(project.provider(() -> classJar)).get());
      }
      for (File classDirectory : classDirectories) {
        directories
            .invoke(testTask)
            .add(project.getLayout().dir(project.provider(() -> classDirectory)).get());
      }
    }
  }
}
