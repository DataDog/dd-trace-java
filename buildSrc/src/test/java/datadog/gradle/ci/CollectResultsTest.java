package datadog.gradle.ci;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

class CollectResultsTest {
  @TempDir Path projectDir;

  @ParameterizedTest
  @ValueSource(strings = {"$Nested", "$Nested$Leaf"})
  void resolvesNestedClassesThroughTheirEnclosingClass(String suffix) throws Exception {
    source(
        "module/src/test/java/example/OuterTest.java",
        "package example; class OuterTest { class Nested { class Leaf {} } }");
    source(
        "module/src/main/java/example/NestedHelper.java", "package example; class NestedHelper {}");
    source("module/src/main/java/example/Leaf.java", "package example; class Leaf {}");
    report("module", "example.OuterTest" + suffix);

    collect();

    assertSource(
        "module", "example.OuterTest" + suffix, "/module/src/test/java/example/OuterTest.java");
  }

  @Test
  void doesNotMatchLongerClassNames() throws Exception {
    source("module/src/test/java/example/OuterTest.java", "package example; class OuterTest {}");
    source(
        "module/src/main/java/example/OuterTestHelper.java",
        "package example; class OuterTestHelper {}");
    report("module", "example.OuterTest");

    collect();

    assertSource("module", "example.OuterTest", "/module/src/test/java/example/OuterTest.java");
  }

  @ParameterizedTest
  @ValueSource(strings = {"interface", "object", "enum class"})
  void resolvesNestedClassesInsideOtherTypes(String declaration) throws Exception {
    String extension = declaration.equals("interface") ? "java" : "kt";
    String path = "module/src/test/" + extension + "/example/OuterTest." + extension;
    source(path, "package example; " + declaration + " OuterTest { ; class Nested {} }");
    report("module", "example.OuterTest$Nested");

    collect();

    assertSource("module", "example.OuterTest$Nested", "/" + path);
  }

  @Test
  void distinguishesClassesInDifferentPackages() throws Exception {
    source("module/src/test/java/example/OuterTest.java", "package example; class OuterTest {}");
    source("module/src/test/java/other/OuterTest.java", "package other; class OuterTest {}");
    report("module", "example.OuterTest");

    collect();

    assertSource("module", "example.OuterTest", "/module/src/test/java/example/OuterTest.java");
  }

  @Test
  void distinguishesPackagesWithTheSameLastSegment() throws Exception {
    source("module/src/test/java/example/OuterTest.java", "package example; class OuterTest {}");
    source(
        "module/src/test/java/other/example/OuterTest.java",
        "package other.example; class OuterTest {}");
    report("module", "example.OuterTest");

    collect();

    assertSource("module", "example.OuterTest", "/module/src/test/java/example/OuterTest.java");
  }

  @Test
  void doesNotUseAClassFromAnotherPackage() throws Exception {
    source("module/src/test/java/other/OuterTest.java", "package other; class OuterTest {}");
    report("module", "example.OuterTest");

    collect();

    assertSource("module", "example.OuterTest", "UNKNOWN");
  }

  @ParameterizedTest
  @ValueSource(strings = {"java", "groovy", "kt", "scala"})
  void resolvesClassesWhoseSourceFileHasADifferentName(String extension) throws Exception {
    source(
        "module/src/test/" + extension + "/example/SharedTests." + extension,
        "package example; class OuterTest {} class AnotherTest {}");
    report("module", "example.OuterTest");

    collect();

    assertSource(
        "module",
        "example.OuterTest",
        "/module/src/test/" + extension + "/example/SharedTests." + extension);
  }

  @Test
  void preservesSourceLayoutsThatDoNotFollowThePackage() throws Exception {
    source("module/src/test/groovy/SharedTests.groovy", "package example; class OuterTest {}");
    report("module", "example.OuterTest");

    collect();

    assertSource("module", "example.OuterTest", "/module/src/test/groovy/SharedTests.groovy");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "$Nested"})
  void preservesDollarSignsInDeclaredClassNames(String suffix) throws Exception {
    source(
        "module/src/test/java/example/Dollar$Test.java",
        "package example; class Dollar$Test { class Nested {} }");
    report("module", "example.Dollar$Test" + suffix);

    collect();

    assertSource(
        "module", "example.Dollar$Test" + suffix, "/module/src/test/java/example/Dollar$Test.java");
  }

  @Test
  void resolvesClassesInTheDefaultPackage() throws Exception {
    source("module/src/test/java/OuterTest.java", "class OuterTest {}");
    report("module", "OuterTest");

    collect();

    assertSource("module", "OuterTest", "/module/src/test/java/OuterTest.java");
  }

  @ParameterizedTest
  @ValueSource(strings = {"example$pkg.MissingTest", "example$pkg.MissingTest$Nested"})
  void doesNotTreatDollarSignsInPackagesAsNestedClasses(String className) throws Exception {
    source("module/src/test/java/example.java", "class example {}");
    report("module", className);

    collect();

    assertSource("module", className, "UNKNOWN");
  }

  @Test
  void reportsUnknownWhenOnlyALongerClassNameExists() throws Exception {
    source(
        "module/src/test/java/example/OuterTestHelper.java",
        "package example; class OuterTestHelper {}");
    report("module", "example.OuterTest");

    collect();

    assertSource("module", "example.OuterTest", "UNKNOWN");
  }

  @Test
  void preservesModuleNamesStartingWithBuild() throws Exception {
    source(
        "build-helper/src/test/java/example/OuterTest.java", "package example; class OuterTest {}");
    report("build-helper", "example.OuterTest");

    collect();

    assertSource(
        "build-helper", "example.OuterTest", "/build-helper/src/test/java/example/OuterTest.java");
  }

  @Test
  void preservesTheModuleFallbackForFeatureReports() throws Exception {
    report("module", "example.OuterTest#feature");

    collect();

    assertSource("module", "example.OuterTest#feature", "module/src");
  }

  private void source(String path, String content) throws IOException {
    Path file = projectDir.resolve(path);
    Files.createDirectories(file.getParent());
    Files.write(file, content.getBytes(UTF_8));
  }

  private void report(String module, String className) throws IOException {
    source(
        "workspace/" + module + "/build/test-results/test/TEST-" + className + ".xml",
        "<testsuite><testcase classname=\""
            + className
            + "\" name=\"passes\" time=\"0.001\"/></testsuite>");
  }

  private void collect() throws Exception {
    Path collector = Paths.get("../.gitlab/collect-result/CollectResults.java").toAbsolutePath();
    Path output = projectDir.resolve("collector.log");
    Process process =
        new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                collector.toString())
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Report collection timed out");
      assertEquals(0, process.exitValue(), new String(Files.readAllBytes(output), UTF_8));
    } finally {
      process.destroyForcibly();
    }
  }

  private void assertSource(String module, String className, String expected) throws Exception {
    Path report = projectDir.resolve("results/" + module + "_test_TEST-" + className + ".xml");
    Element testcase =
        (Element)
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(report.toFile())
                .getElementsByTagName("testcase")
                .item(0);
    assertEquals(expected, testcase.getAttribute("file"));
  }
}
