package datadog.trace.agent.tooling.matchercache.classfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class ClassVersionsTest {

  @Test
  void testDefaultVersionOnly() {
    ClassVersions cv = new ClassVersions("foo.bar.FooBar");

    byte[] bytes = new byte[] {1, 2, 3};
    cv.addClassBytes(bytes, "foo/bar/FooBar.class", "");

    assertEquals(bytes, cv.classBytes(7));
    assertEquals(bytes, cv.classBytes(8));
    assertEquals(bytes, cv.classBytes(9));
    assertEquals(bytes, cv.classBytes(11));
  }

  @Test
  void testSpecificVersionOnly() {
    ClassVersions cv = new ClassVersions("foo.bar.FooBar");

    byte[] bytes9 = new byte[] {1, 2, 3};
    cv.addClassBytes(bytes9, "META-INF/versions/9/foo/bar/FooBar.class", "");

    assertNull(cv.classBytes(7));
    assertNull(cv.classBytes(8));
    assertEquals(bytes9, cv.classBytes(9));
    assertEquals(bytes9, cv.classBytes(11));
  }

  @Test
  void testMultipleVersions() {
    ClassVersions cv = new ClassVersions("foo.bar.FooBar");

    // insertion order shouldn't matter
    byte[] bytes11 = new byte[] {7, 8, 9};
    cv.addClassBytes(bytes11, "META-INF/versions/11/foo/bar/FooBar.class", "");

    byte[] bytes7 = new byte[] {1, 2, 3};
    cv.addClassBytes(bytes7, "foo/bar/FooBar.class", "");

    byte[] bytes9 = new byte[] {4, 5, 6};
    cv.addClassBytes(bytes9, "META-INF/versions/9/foo/bar/FooBar.class", "");

    assertEquals(bytes7, cv.classBytes(7));
    assertEquals(bytes7, cv.classBytes(8));
    assertEquals(bytes9, cv.classBytes(9));
    // return closest compatible version for non existing version
    assertEquals(bytes9, cv.classBytes(10));
    assertEquals(bytes11, cv.classBytes(11));
    // return closest compatible version for non existing version
    assertEquals(bytes11, cv.classBytes(14));
  }

  @Test
  void testConflictingVersionsWarning() {
    // TODO
  }
}
