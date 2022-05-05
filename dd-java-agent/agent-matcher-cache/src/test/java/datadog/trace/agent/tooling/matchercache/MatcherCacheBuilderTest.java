package datadog.trace.agent.tooling.matchercache;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollection;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollectionLoader;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassFinder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;

public class MatcherCacheBuilderTest {
  public static final String TEST_CLASSES_FOLDER = "build/resources/test/test-classes";

  private static class TestClassLoader extends ClassCollectionLoader {
    public TestClassLoader(ClassCollection classCollection, int javaMajorVersion) {
      super(classCollection, javaMajorVersion);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if ("example.InnerJarClass".equals(name)) {
        throw new RuntimeException("Intentional test class load failure");
      }
      return super.loadClass(name, resolve);
    }
  }

  private static class TestClassMatchers implements ClassMatchers {
    @Override
    public boolean matchesAny(Class<?> cl) {
      TypeDescription typeDescription = TypeDescription.ForLoadedType.of(cl);
      String fqcn = typeDescription.getCanonicalName();
      if (fqcn != null) {
        switch (fqcn) {
          case "example.OuterJarClass":
          case "example.InnerJarClass":
          case "example.classes.Abc":
          case "foo.bar.FooBar":
            return true;
          case "example.MiddleJarClass":
          case "example.classes.Only9":
            return false;
        }
      }
      return false;
    }

    @Override
    public boolean isGloballyIgnored(String fqcn) {
      switch (fqcn) {
        case "foo.bar.xyz.Xyz":
        case "bar.foo.Baz":
          return true;
      }
      return false;
    }
  }

  @Test
  public void test() throws IOException {
    File classPath = new File(TEST_CLASSES_FOLDER);
    assertTrue(classPath.exists());

    ClassFinder classFinder = new ClassFinder();
    ClassCollection classCollection = classFinder.findClassesIn(classPath);
    int javaMajorVersion = 9;
    ClassLoader classLoader = new TestClassLoader(classCollection, javaMajorVersion);
    MatcherCacheBuilder matcherCacheBuilder = new MatcherCacheBuilder(javaMajorVersion);
    MatcherCacheBuilder.Stats stats =
        matcherCacheBuilder.fill(classCollection, classLoader, new TestClassMatchers());

    assertEquals(2, stats.ignoredClassesCounter);
    assertEquals(2, stats.skippedClassesCounter);
    assertEquals(3, stats.transformedClassesCounter);
    assertEquals(1, stats.failedCounterCounter);

    // serialize MatcherCache and load as MatcherCache and check the result
    MatcherCache matcherCache = serializeAndLoadCacheData(matcherCacheBuilder);

    assertEquals(MatcherCache.Result.TRANSFORM, matcherCache.transform("example.OuterJarClass"));
    assertEquals(MatcherCache.Result.TRANSFORM, matcherCache.transform("example.InnerJarClass"));
    assertEquals(MatcherCache.Result.SKIP, matcherCache.transform("example.MiddleJarClass"));
    assertEquals(MatcherCache.Result.SKIP, matcherCache.transform("example.NonExistingClass"));

    assertEquals(MatcherCache.Result.TRANSFORM, matcherCache.transform("example.classes.Abc"));
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
    String expectedTextReport =
        "Packages: 5\n"
            + "bar.foo.Baz,IGNORE,build/resources/test/test-classes/relocated-classes/somefolder/Baz.class\n"
            + "example.InnerJarClass,FAIL,build/resources/test/test-classes/inner-jars/example.jar/Middle.jar/InnerJarClass.jar"
            + ",java.lang.RuntimeException: Intentional test class load failure\n"
            + "example.MiddleJarClass,SKIP,build/resources/test/test-classes/inner-jars/example.jar/Middle.jar\n"
            + "example.OuterJarClass,TRANSFORM,build/resources/test/test-classes/inner-jars/example.jar\n"
            + "example.classes.Abc,TRANSFORM,build/resources/test/test-classes/multi-release-jar/multi-release.jar\n"
            + "example.classes.Only9,SKIP,build/resources/test/test-classes/multi-release-jar/multi-release.jar\n"
            + "foo.bar.FooBar,TRANSFORM,build/resources/test/test-classes/renamed-class-file/renamed-foobar-class.bin\n"
            + "foo.bar.xyz.Xyz,IGNORE,build/resources/test/test-classes/standard-layout/foo/bar/xyz/Xyz.class\n";
    assertEquals(expectedTextReport, reportData);
  }

  private String serializeTextReport(MatcherCacheBuilder matcherCacheBuilder) {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    matcherCacheBuilder.serializeText(os);
    return new String(os.toByteArray(), StandardCharsets.UTF_8);
  }

  private MatcherCache serializeAndLoadCacheData(MatcherCacheBuilder matcherCacheBuilder)
      throws IOException {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    matcherCacheBuilder.serializeBinary(os);
    ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
    return MatcherCache.deserialize(is);
  }
}
