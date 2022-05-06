package datadog.trace.agent.tooling.matchercache.classfinder;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ClassFinderJava11Test extends ClassFinderTest {

  public static final String TEST_CLASSES_FOLDER = "build/resources/test/test-classes-11";

  private final ClassFinder classFinder = new ClassFinder();

  @Test
  void testJavaModule() throws IOException {
    ClassCollection classCollection =
        classFinder.findClassesIn(new File(TEST_CLASSES_FOLDER, "java-module"));

    Set<String> expectedClasses = new HashSet<>();
    expectedClasses.add("org.company.Abc");

    assertClasses(expectedClasses, classCollection.allClasses(7));
    assertClasses(expectedClasses, classCollection.allClasses(8));
    assertClasses(expectedClasses, classCollection.allClasses(9));
    assertClasses(expectedClasses, classCollection.allClasses(11));
  }
}
