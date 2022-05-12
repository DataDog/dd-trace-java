package datadog.trace.agent.tooling.matchercache.classfinder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ClassCollectionTest {

  @Test
  public void testAddAndFind() {
    ClassCollection.Builder ccb = new ClassCollection.Builder();

    byte[] bytesDefault = new byte[] {1, 2, 3};
    ccb.addClass(bytesDefault, "foo.bar.FooBar", "foo/bar/FooBar.class", "");

    byte[] bytesJava9 = new byte[] {4, 5, 6};
    byte[] bytesJavaOnly9 = new byte[] {7, 8, 9};
    ccb.addClass(bytesJava9, "foo.bar.FooBar", "META-INF/versions/9/foo/bar/FooBar.class", "");
    ccb.addClass(bytesJavaOnly9, "foo.bar.Only9", "META-INF/versions/9/foo/bar/Only9.class", "");

    ClassCollection cc = ccb.buildAndReset();

    ClassData cvs = cc.findClassData("foo.bar.FooBar");
    assertEquals(bytesDefault, cvs.classBytes(7));
    assertEquals(bytesJava9, cvs.classBytes(9));
    assertEquals(bytesJava9, cvs.classBytes(11));

    ClassData cvs9 = cc.findClassData("foo.bar.Only9");
    assertNull(cvs9.classBytes(7));
    assertNull(cvs9.classBytes(8));
    assertEquals(bytesJavaOnly9, cvs9.classBytes(9));
    assertEquals(bytesJavaOnly9, cvs9.classBytes(11));

    assertNull(cc.findClassData("foo.bar.Baz"));
    assertNull(cc.findClassData("bar.Bar"));
  }

  @Test
  public void testAllClasses() {
    ClassCollection.Builder ccb = new ClassCollection.Builder();

    byte[] bytes = new byte[] {1, 2, 3};
    ccb.addClass(bytes, "foo.bar.FooBar", "foo/bar/FooBar.class", "");
    ccb.addClass(bytes, "bar.Bar", "bar/Bar.class", "");

    ClassCollection cc = ccb.buildAndReset();

    Set<String> expectedClasses = new HashSet<>();
    expectedClasses.add("bar.Bar");
    expectedClasses.add("foo.bar.FooBar");

    assertClasses(expectedClasses, cc.allClasses(7));
  }

  @Test
  public void testAllClassesMultiRelease() {
    ClassCollection.Builder ccb = new ClassCollection.Builder();

    byte[] bytes = new byte[] {1, 2, 3};
    ccb.addClass(bytes, "foo.bar.FooBar", "foo/bar/FooBar.class", "");
    ccb.addClass(bytes, "bar.Bar", "bar/Bar.class", "");
    byte[] bytesJava9 = new byte[] {4, 5, 6};
    ccb.addClass(bytesJava9, "foo.bar.FooBar9", "META-INF/versions/9/foo/bar/FooBar9.class", "");

    ClassCollection cc = ccb.buildAndReset();

    Set<String> expectedClasses = new HashSet<>();
    expectedClasses.add("bar.Bar");
    expectedClasses.add("foo.bar.FooBar");

    assertClasses(expectedClasses, cc.allClasses(7));
    assertClasses(expectedClasses, cc.allClasses(8));

    expectedClasses.add("foo.bar.FooBar9");
    assertClasses(expectedClasses, cc.allClasses(9));
    assertClasses(expectedClasses, cc.allClasses(11));
  }

  @Test
  public void testClassCollectionWithParent() {
    byte[] fooBytes = new byte[] {1, 2, 3};
    byte[] barBytes = new byte[] {2, 3, 4};
    String foo = "foo.bar.FooBar";
    String bar = "bar.Bar";

    ClassCollection.Builder ccb = new ClassCollection.Builder();

    ccb.addClass(fooBytes, foo, "foo/bar/FooBar.class", "");
    ClassCollection ccFoo = ccb.buildAndReset();

    ccb.addClass(barBytes, bar, "bar/Bar.class", "");
    ClassCollection ccBar = ccb.buildAndReset();

    assertClasses(Collections.singleton(foo), ccFoo.allClasses(7));
    assertEquals(fooBytes, ccFoo.findClassData(foo).classBytes(7));
    assertNull(ccFoo.findClassData(bar));

    assertClasses(Collections.singleton(bar), ccBar.allClasses(7));
    assertEquals(barBytes, ccBar.findClassData(bar).classBytes(7));
    assertNull(ccBar.findClassData(foo));

    ClassCollection ccFooBar = ccFoo.withParent(ccBar);
    assertClasses(Collections.singleton(foo), ccFooBar.allClasses(7));
    assertEquals(fooBytes, ccFooBar.findClassData(foo).classBytes(7));
    assertEquals(barBytes, ccFooBar.findClassData(bar).classBytes(7));

    ClassCollection ccBarFoo = ccBar.withParent(ccFoo);
    assertClasses(Collections.singleton(bar), ccBarFoo.allClasses(7));
    assertEquals(fooBytes, ccBarFoo.findClassData(foo).classBytes(7));
    assertEquals(barBytes, ccBarFoo.findClassData(bar).classBytes(7));
  }

  private void assertClasses(Set<String> expectedClasses, Set<ClassData> actualClassData) {
    Set<String> actualClasses = new HashSet<>();
    for (ClassData cd : actualClassData) {
      actualClasses.add(cd.getFullClassName());
    }
    assertEquals(expectedClasses, actualClasses);
  }
}
