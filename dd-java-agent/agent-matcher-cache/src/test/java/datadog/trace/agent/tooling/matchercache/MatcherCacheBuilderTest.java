package datadog.trace.agent.tooling.matchercache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollection;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassFinder;
import datadog.trace.agent.tooling.matchercache.classfinder.TypeResolver;
import datadog.trace.agent.tooling.matchercache.util.BinarySerializers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MatcherCacheBuilderTest {
  public final String TEST_CLASSES_FOLDER =
      this.getClass().getClassLoader().getResource("test-classes").getFile();

  private static class TestClassMatchers implements ClassMatchers {
    @Override
    public boolean isGloballyIgnored(String fullClassName, boolean skipAdditionalIgnores) {
      if (!skipAdditionalIgnores) {
        switch (fullClassName) {
          case "example.classes.Abc":
            return true;
        }
      }
      switch (fullClassName) {
        case "foo.bar.xyz.Xyz":
        case "bar.foo.Baz":
          return true;
      }
      return false;
    }

    @Override
    public String matchingIntrumenters(TypeDescription typeDescription) {
      String fullClassName = typeDescription.getCanonicalName();
      if (fullClassName != null) {
        switch (fullClassName) {
          case "example.OuterJarClass":
          case "example.InnerJarClass":
          case "example.classes.Abc":
          case "foo.bar.FooBar":
            return "TestInstrumenters";
          case "example.MiddleJarClass":
          case "example.classes.Only9":
            return null;
        }
      }
      return null;
    }
  }

  @Test
  public void testHappyPath() throws IOException {
    File classPath = new File(TEST_CLASSES_FOLDER);

    ClassFinder classFinder = new ClassFinder();
    ClassCollection classCollection = classFinder.findClassesIn(classPath);
    int javaMajorVersion = 9;
    String agentVersion = "0.95.0";
    MatcherCacheBuilder matcherCacheBuilder =
        new MatcherCacheBuilder(new TestClassMatchers(), javaMajorVersion, agentVersion) {
          @Override
          protected TypeResolver getTypeResolver(ClassCollection classCollection) {
            return new TypeResolver(classCollection, javaMajorVersion) {
              @Override
              public TypeDescription typeDescription(String fullClassName) {
                switch (fullClassName) {
                  case "example.InnerJarClass":
                  case "foo.bar.FooBar":
                    throw new RuntimeException("Intentional test class load failure");
                }
                return super.typeDescription(fullClassName);
              }
            };
          }
        };

    MatcherCacheBuilder.Stats stats = matcherCacheBuilder.fill(classCollection);

    assertEquals("Ignore: 3; Skip: 2; Transform: 1; Fail: 2", stats.toString());

    // serialize MatcherCache and load as MatcherCache and check the result
    MatcherCache matcherCache =
        serializeAndLoadCacheData(matcherCacheBuilder, javaMajorVersion, agentVersion);

    assertEquals(MatcherCache.Result.TRANSFORM, matcherCache.transform("example.OuterJarClass"));
    assertEquals(MatcherCache.Result.TRANSFORM, matcherCache.transform("example.InnerJarClass"));
    assertEquals(MatcherCache.Result.SKIP, matcherCache.transform("example.MiddleJarClass"));
    assertEquals(MatcherCache.Result.SKIP, matcherCache.transform("example.NonExistingClass"));

    assertEquals(MatcherCache.Result.SKIP, matcherCache.transform("example.classes.Abc"));
    assertEquals(MatcherCache.Result.SKIP, matcherCache.transform("example.classes.Only9"));
    assertEquals(
        MatcherCache.Result.SKIP, matcherCache.transform("example.classes.NonExistingClass"));

    assertEquals(MatcherCache.Result.SKIP, matcherCache.transform("foo.bar.Baz"));
    assertEquals(MatcherCache.Result.TRANSFORM, matcherCache.transform("foo.bar.FooBar"));
    assertEquals(MatcherCache.Result.SKIP, matcherCache.transform("foo.bar.NonExistingClass"));

    assertEquals(MatcherCache.Result.SKIP, matcherCache.transform("foo.bar.xyz.Xyz"));
    assertEquals(MatcherCache.Result.SKIP, matcherCache.transform("foo.bar.xyz.NonExistingClass"));

    assertEquals(MatcherCache.Result.UNKNOWN, matcherCache.transform("non.existing.package.Foo"));
    assertEquals(MatcherCache.Result.UNKNOWN, matcherCache.transform("non.existing.package.Bar"));

    // serialize text report
    String reportData = serializeTextReport(matcherCacheBuilder);

    assertLines(
        reportData,
        "Matcher Cache Format 1, Tracer 0\\.95\\.0, Java 9",
        "bar.foo.Baz,IGNORE,[^,]*/test-classes/relocated-classes/somefolder/Baz.class",
        "example.InnerJarClass,FAIL,java.lang.RuntimeException: Intentional test class load failure,[^,]*/test-classes/inner-jars/example.jar/Middle.jar/InnerJarClass.jar",
        "example.MiddleJarClass,SKIP,[^,]*/test-classes/inner-jars/example.jar/Middle.jar",
        "example.OuterJarClass,TRANSFORM,TestInstrumenters,[^,]*/test-classes/inner-jars/example.jar",
        "example.classes.Abc,IGNORE,AdditionalIgnores,TestInstrumenters,[^,]*/test-classes/multi-release-jar/multi-release.jar",
        "example.classes.Only9,SKIP,[^,]*/test-classes/multi-release-jar/multi-release.jar",
        "foo.bar.FooBar,FAIL,java.lang.RuntimeException: Intentional test class load failure,[^,]*/test-classes/renamed-class-file/renamed-foobar-class.bin",
        "foo.bar.xyz.Xyz,IGNORE,[^,]*/test-classes/standard-layout/foo/bar/xyz/Xyz.class");
  }

  private void assertLines(String actual, String... expectedLines) {
    String[] actualLines = actual.split("\n");
    assertEquals(
        expectedLines.length,
        actualLines.length,
        "Expected number of lines doesn't match actual number of lines: \n" + actual);
    for (int i = 0; i < expectedLines.length; i++) {
      String expectedLine = expectedLines[i];
      String actualLine = actualLines[i];
      assertTrue(
          Pattern.compile(expectedLine).matcher(actualLine).matches(),
          "Actual line:\n" + actualLine + "\nDoes Not match expected:\n" + expectedLine);
    }
  }

  @Test
  public void testFailIfUnexpectedDataFormatVersion() throws IOException {
    int incorrectMatcherCacheDataFormat = MatcherCacheBuilder.MATCHER_CACHE_FILE_FORMAT_VERSION + 1;
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    BinarySerializers.writeInt(os, incorrectMatcherCacheDataFormat);
    ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
    int whateverJavaVersion = 11;
    Assertions.assertThrows(
        MatcherCache.UnexpectedDataFormatVersion.class,
        () -> MatcherCache.deserialize(is, whateverJavaVersion, "whatever-agent-version"));
  }

  @Test
  public void testFailIfCacheDataIsForAnotherJavaVersion() {
    int javaMajorVersion = 9;
    MatcherCacheBuilder matcherCacheBuilder =
        new MatcherCacheBuilder(
            new TestClassMatchers(), javaMajorVersion, "whatever-agent-version");

    int anotherJavaMajorVersion = 8;
    assertNotEquals(anotherJavaMajorVersion, javaMajorVersion);

    Assertions.assertThrows(
        MatcherCache.IncompatibleJavaVersionData.class,
        () ->
            serializeAndLoadCacheData(
                matcherCacheBuilder, anotherJavaMajorVersion, "whatever-agent-version"));
  }

  @Test
  public void testFailIfCacheDataBuiltWithDifferentAgentVersion() throws IOException {
    int javaMajorVersion = 11;
    MatcherCacheBuilder matcherCacheBuilder =
        new MatcherCacheBuilder(
            new TestClassMatchers(), javaMajorVersion, "whatever-agent-version");

    String agentVersion = "0.95.0";
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    matcherCacheBuilder.serializeBinary(os);

    String anotherAgentVersion = "0.95.1";
    assertNotEquals(anotherAgentVersion, agentVersion);
    Assertions.assertThrows(
        MatcherCache.IncompatibleTracerVersion.class,
        () -> {
          ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
          MatcherCache.deserialize(is, javaMajorVersion, anotherAgentVersion);
        });
  }

  private String serializeTextReport(MatcherCacheBuilder matcherCacheBuilder) {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    matcherCacheBuilder.serializeText(os);
    return new String(os.toByteArray(), StandardCharsets.UTF_8);
  }

  private MatcherCache serializeAndLoadCacheData(
      MatcherCacheBuilder matcherCacheBuilder, int javaMajorVersion, String agentVersion)
      throws IOException {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    matcherCacheBuilder.serializeBinary(os);
    ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
    return MatcherCache.deserialize(is, javaMajorVersion, agentVersion);
  }
}
