package datadog.trace.agent.tooling.matchercache;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollection;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollectionLoader;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassFinder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MatcherCacheBuilderTest {
  public static final String TEST_CLASSES_FOLDER = "build/resources/test/test-classes";

  MatcherCacheBuilder matcherCacheBuilder;
  int javaMajorVersion = 9;

  @BeforeEach
  public void initMatcherCacheBuilder() {
    matcherCacheBuilder =
        new MatcherCacheBuilder(javaMajorVersion) {
          @Override
          protected int classHash(String className) {
            if ("MiddleJarClass".equals(className)) {
              // fake class name hash code collision
              return super.classHash("OuterJarClass");
            }
            return super.classHash(className);
          }
        };
  }

  @Test
  public void test() throws IOException {
    ClassFinder classFinder = new ClassFinder();
    ClassCollection classCollection = classFinder.findClassesIn(new File(TEST_CLASSES_FOLDER));

    ClassLoader classLoader = new ClassCollectionLoader(classCollection, javaMajorVersion);

    ClassMatchers classMatchers =
        new ClassMatchers() {
          @Override
          public boolean matchesAny(Class<?> cl) {
            TypeDescription typeDescription = TypeDescription.ForLoadedType.of(cl);
            String className = typeDescription.getCanonicalName();
            if (className != null) {
              switch (className) {
                case "example.OuterJarClass":
                case "example.InnerJarClass":
                case "example.classes.Abc":
                case "foo.bar.FooBar":
                  return true;
                case "example.MiddleJarClass":
                case "example.classes.Only9":
                case "foo.bar.xyz.Xyz":
                case "foo.bar.Baz":
                  return false;
              }
            }
            return false;
          }

          @Override
          public boolean isGloballyIgnored(String fqcn) {
            // TODO test
            return false;
          }
        };

    MatcherCacheBuilder.Stats stats =
        matcherCacheBuilder.fill(classCollection, classLoader, classMatchers);

    // TODO implement test for ignored classes
    assertEquals(0, stats.ignoredClassesCounter);
    assertEquals(4, stats.skippedClassesCounter);
    assertEquals(4, stats.transformedClassesCounter);
    // TODO implement test for failed classes
    assertEquals(0, stats.failedCounterCounter);
    // serialize MatcherCache and load as MatcherCache and check the result
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    matcherCacheBuilder.serializeBinary(os);

    ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
    MatcherCache matcherCache = MatcherCache.deserialize(is);

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
  }

  // TODO check package overlap between different class-collections (should warn about it and
  // probably exclude such packages from the matcher)

  // TODO test addSkippedPackage
}
