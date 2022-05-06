package datadog.trace.agent.tooling.matchercache.classfinder;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class ClassCollectionLoaderJava11Test extends ClassCollectionLoaderTest {
  public static final String TEST_CLASSES_FOLDER = "build/resources/test/test-classes-11";

  private ClassFinder classFinder = new ClassFinder();

  @Test
  void testJavaModule() throws IOException, ClassNotFoundException {
    ClassCollectionLoader ccl = createClassLoader("java-module", 11);
    assertClass("org.company.Abc", "org.company.Abc", ccl);
  }

  private ClassCollectionLoader createClassLoader(String testClassesSubFolder, int javaMajorVersion)
      throws IOException {
    ClassCollection classCollection =
        classFinder.findClassesIn(new File(TEST_CLASSES_FOLDER, testClassesSubFolder));
    return new ClassCollectionLoader(classCollection, javaMajorVersion);
  }
}
