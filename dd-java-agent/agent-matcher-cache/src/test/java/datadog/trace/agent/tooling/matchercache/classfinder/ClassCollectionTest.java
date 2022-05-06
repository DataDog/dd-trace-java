package datadog.trace.agent.tooling.matchercache.classfinder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ClassCollectionTest {

  @Test
  public void testAddAndFind() {
    ClassCollection cc = new ClassCollection();

    assertNull(cc.findClass("foo.bar.FooBar"));

    byte[] bytesDefault = new byte[] {1, 2, 3};
    cc.addClass(bytesDefault, "foo.bar.FooBar", "foo/bar/FooBar.class", "");

    byte[] bytesJava9 = new byte[] {4, 5, 6};
    byte[] bytesJavaOnly9 = new byte[] {7, 8, 9};
    cc.addClass(bytesJava9, "foo.bar.FooBar", "META-INF/versions/9/foo/bar/FooBar.class", "");
    cc.addClass(bytesJavaOnly9, "foo.bar.Only9", "META-INF/versions/9/foo/bar/Only9.class", "");

    ClassData cvs = cc.findClass("foo.bar.FooBar");
    assertEquals(bytesDefault, cvs.classBytes(7));
    assertEquals(bytesJava9, cvs.classBytes(9));
    assertEquals(bytesJava9, cvs.classBytes(11));

    ClassData cvs9 = cc.findClass("foo.bar.Only9");
    assertNull(cvs9.classBytes(7));
    assertNull(cvs9.classBytes(8));
    assertEquals(bytesJavaOnly9, cvs9.classBytes(9));
    assertEquals(bytesJavaOnly9, cvs9.classBytes(11));

    assertNull(cc.findClass("foo.bar.Baz"));
    assertNull(cc.findClass("bar.Bar"));
  }

  @Test
  public void testAllClasses() {
    ClassCollection cc = new ClassCollection();

    assertTrue(cc.allClasses(7).isEmpty());

    byte[] bytes = new byte[] {1, 2, 3};
    cc.addClass(bytes, "foo.bar.FooBar", "foo/bar/FooBar.class", "");
    cc.addClass(bytes, "bar.Bar", "bar/Bar.class", "");

    Set<String> expectedClasses = new HashSet<>();
    expectedClasses.add("bar.Bar");
    expectedClasses.add("foo.bar.FooBar");

    assertClasses(expectedClasses, cc.allClasses(7));
  }

  @Test
  public void testAllClassesMultiRelease() {
    ClassCollection cc = new ClassCollection();

    assertTrue(cc.allClasses(7).isEmpty());

    byte[] bytes = new byte[] {1, 2, 3};
    cc.addClass(bytes, "foo.bar.FooBar", "foo/bar/FooBar.class", "");
    cc.addClass(bytes, "bar.Bar", "bar/Bar.class", "");
    byte[] bytesJava9 = new byte[] {4, 5, 6};
    cc.addClass(bytesJava9, "foo.bar.FooBar9", "META-INF/versions/9/foo/bar/FooBar9.class", "");

    Set<String> expectedClasses = new HashSet<>();
    expectedClasses.add("bar.Bar");
    expectedClasses.add("foo.bar.FooBar");

    assertClasses(expectedClasses, cc.allClasses(7));
    assertClasses(expectedClasses, cc.allClasses(8));

    expectedClasses.add("foo.bar.FooBar9");
    assertClasses(expectedClasses, cc.allClasses(9));
    assertClasses(expectedClasses, cc.allClasses(11));
  }

  private void assertClasses(Set<String> expectedClasses, Set<ClassData> actualClassData) {
    Set<String> actualClasses = new HashSet<>();
    for (ClassData cd : actualClassData) {
      actualClasses.add(cd.getFullClassName());
    }
    assertEquals(expectedClasses, actualClasses);
  }
}
