package datadog.trace.agent.tooling.matchercache.classfinder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

public class ClassCollectionLoaderTest {
  public final String TEST_CLASSES_FOLDER =
      this.getClass().getClassLoader().getResource("test-classes").getFile();
  public final String TEST_CLASSES_JAVA_11_FOLDER =
      this.getClass().getClassLoader().getResource("test-classes-11").getFile();

  public void assertClass(
      String expectedToString, String className, ClassCollectionLoader classCollectionLoader)
      throws ClassNotFoundException {
    Class<?> innerJarClass = classCollectionLoader.loadClass(className, false);
    assertNotNull(innerJarClass);
    Object instance;
    try {
      instance = innerJarClass.newInstance();
    } catch (InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
    assertEquals(expectedToString, instance.toString());
  }

  private final ClassFinder classFinder = new ClassFinder();

  @Test
  public void testInnerJars() throws IOException, ClassNotFoundException {
    ClassCollectionLoader ccl = createClassLoader(TEST_CLASSES_FOLDER, "inner-jars", 7);

    assertClass("InnerJarClass", "example.InnerJarClass", ccl);
    assertClass("MiddleJarClass", "example.MiddleJarClass", ccl);
    assertClass("OuterJarClass", "example.OuterJarClass", ccl);
  }

  @Test
  public void testInnerJars9() throws IOException, ClassNotFoundException {
    ClassCollectionLoader ccl = createClassLoader(TEST_CLASSES_FOLDER, "inner-jars", 9);

    assertClass("InnerJarClass", "example.InnerJarClass", ccl);
    assertClass("MiddleJarClass", "example.MiddleJarClass", ccl);
    assertClass("OuterJarClass9", "example.OuterJarClass", ccl);
  }

  @Test
  void testMultiReleaseClasses() throws IOException, ClassNotFoundException {
    ClassCollectionLoader ccl = createClassLoader(TEST_CLASSES_FOLDER, "multi-release-jar", 8);

    assertClass("Abc", "example.classes.Abc", ccl);

    assertClassNotFound(ccl, "example.classes.Only9");
  }

  @Test
  void testMultiReleaseClasses9() throws IOException, ClassNotFoundException {
    ClassCollectionLoader ccl = createClassLoader(TEST_CLASSES_FOLDER, "multi-release-jar", 9);
    //    assertTrue(false);
    assertClass("Abc9", "example.classes.Abc", ccl);
    assertClass("Only9", "example.classes.Only9", ccl);
  }

  @Test
  void testRelocatedClasses() throws IOException, ClassNotFoundException {
    ClassCollectionLoader ccl = createClassLoader(TEST_CLASSES_FOLDER, "relocated-classes", 7);

    assertClass("bar.foo.Baz", "bar.foo.Baz", ccl);
  }

  @Test
  void testRenamedClassFile() throws IOException, ClassNotFoundException {
    ClassCollectionLoader ccl = createClassLoader(TEST_CLASSES_FOLDER, "renamed-class-file", 8);

    assertClass("FooBar", "foo.bar.FooBar", ccl);
  }

  @Test
  void testStandardLayout() throws IOException, ClassNotFoundException {
    ClassCollectionLoader ccl = createClassLoader(TEST_CLASSES_FOLDER, "standard-layout", 11);

    assertClass("XYZ", "foo.bar.xyz.Xyz", ccl);
  }

  @Test
  void testAllTogether() throws IOException, ClassNotFoundException {
    ClassCollectionLoader ccl = createClassLoader(TEST_CLASSES_FOLDER, "/", 8);

    assertClass("InnerJarClass", "example.InnerJarClass", ccl);
    assertClass("MiddleJarClass", "example.MiddleJarClass", ccl);
    assertClass("OuterJarClass", "example.OuterJarClass", ccl);

    assertClass("InnerJarClass", "example.InnerJarClass", ccl);

    assertClass("Abc", "example.classes.Abc", ccl);
    assertClassNotFound(ccl, "example.classes.Only9");

    assertClass("FooBar", "foo.bar.FooBar", ccl);

    assertClass("XYZ", "foo.bar.xyz.Xyz", ccl);
  }

  @Test
  void testAllTogether9() throws IOException, ClassNotFoundException {
    ClassCollectionLoader ccl = createClassLoader(TEST_CLASSES_FOLDER, "/", 9);

    assertClass("InnerJarClass", "example.InnerJarClass", ccl);
    assertClass("MiddleJarClass", "example.MiddleJarClass", ccl);
    assertClass("OuterJarClass9", "example.OuterJarClass", ccl);

    assertClass("InnerJarClass", "example.InnerJarClass", ccl);

    assertClass("Abc9", "example.classes.Abc", ccl);
    assertClass("Only9", "example.classes.Only9", ccl);

    assertClass("bar.foo.Baz", "bar.foo.Baz", ccl);

    assertClass("FooBar", "foo.bar.FooBar", ccl);

    assertClass("XYZ", "foo.bar.xyz.Xyz", ccl);
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_11)
  void testJavaModule() throws IOException, ClassNotFoundException {
    ClassCollectionLoader ccl = createClassLoader(TEST_CLASSES_JAVA_11_FOLDER, "java-module", 11);
    assertClass("org.company.Abc", "org.company.Abc", ccl);
  }

  private ClassCollectionLoader createClassLoader(
      String testClassesFolder, String testClassesSubFolder, int javaMajorVersion)
      throws IOException {
    ClassCollection classCollection =
        classFinder.findClassesIn(new File(testClassesFolder, testClassesSubFolder));
    return new ClassCollectionLoader(classCollection, javaMajorVersion);
  }

  private void assertClassNotFound(ClassCollectionLoader ccl, String className) {
    try {
      ccl.loadClass(className, true);
      fail("Expected ClassNotFoundException");
    } catch (ClassNotFoundException e) {
      // ignore
    }
  }
}
