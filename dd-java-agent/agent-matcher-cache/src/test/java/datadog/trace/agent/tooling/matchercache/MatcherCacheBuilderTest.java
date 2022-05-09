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
import java.util.regex.Pattern;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;

public class MatcherCacheBuilderTest {
  public final String TEST_CLASSES_FOLDER =
      this.getClass().getClassLoader().getResource("test-classes").getFile();

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
      String fullClassName = typeDescription.getCanonicalName();
      if (fullClassName != null) {
        switch (fullClassName) {
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
    public boolean isGloballyIgnored(String fullClassName) {
      switch (fullClassName) {
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

    ClassFinder classFinder = new ClassFinder();
    ClassCollection classCollection = classFinder.findClassesIn(classPath);
    int javaMajorVersion = 9;
    ClassLoader classLoader = new TestClassLoader(classCollection, javaMajorVersion);
    MatcherCacheBuilder matcherCacheBuilder = new MatcherCacheBuilder(javaMajorVersion);
    MatcherCacheBuilder.Stats stats =
        matcherCacheBuilder.fill(classCollection, classLoader, new TestClassMatchers());

    assertEquals("Ignore: 2; Skip: 2; Transform: 3; Fail: 1", stats.toString());

    matcherCacheBuilder.optimize();

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
    Pattern expectedPattern =
        Pattern.compile(
            "Packages: 5\n"
                + "bar.foo.Baz,IGNORE,.*/test-classes/relocated-classes/somefolder/Baz.class\n"
                + "example.InnerJarClass,FAIL,.*/test-classes/inner-jars/example.jar/Middle.jar/InnerJarClass.jar"
                + ",java.lang.RuntimeException: Intentional test class load failure\n"
                + "example.MiddleJarClass,SKIP,.*/test-classes/inner-jars/example.jar/Middle.jar\n"
                + "example.OuterJarClass,TRANSFORM,.*/test-classes/inner-jars/example.jar\n"
                + "example.classes.Abc,TRANSFORM,.*/test-classes/multi-release-jar/multi-release.jar\n"
                + "example.classes.Only9,SKIP,.*/test-classes/multi-release-jar/multi-release.jar\n"
                + "foo.bar.FooBar,TRANSFORM,.*/test-classes/renamed-class-file/renamed-foobar-class.bin\n"
                + "foo.bar.xyz.Xyz,IGNORE,.*/test-classes/standard-layout/foo/bar/xyz/Xyz.class\n");
    String reportData = serializeTextReport(matcherCacheBuilder);
    assertTrue(
        expectedPattern.matcher(reportData).matches(),
        "Expected:\n" + expectedPattern + "Actual:\n" + reportData);
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
