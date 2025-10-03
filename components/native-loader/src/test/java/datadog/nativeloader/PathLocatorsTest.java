package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/*
 * These tests mostly exist to satisfy coverage checks.
 * Although equals is used in other tests & usual contract obliges defining hashCode, too
 */
public class PathLocatorsTest {
  @Test
  public void dirBased_equals() {
    PathLocator dirLocator1 = PathLocators.fromLibDirs("foo");
    PathLocator dirLocator2 = PathLocators.fromLibDirs(new File("foo"));
    PathLocator dirLocator3 = PathLocators.fromLibDirs(Paths.get("foo"));

    assertEqualsAndHashCode(dirLocator1, dirLocator2);
    assertEqualsAndHashCode(dirLocator1, dirLocator3);
  }

  @Test
  public void dirBased_notEquals() {
    PathLocator dirLocator1 = PathLocators.fromLibDirs("foo1");
    PathLocator dirLocator2 = PathLocators.fromLibDirs(new File("foo2"));
    PathLocator dirLocator3 = PathLocators.fromLibDirs(Paths.get("foo3"));

    assertNotEquals(dirLocator1, dirLocator2);
    assertNotEquals(dirLocator1, dirLocator3);
  }

  @Test
  public void dirBased_diffType_notEquals() {
    PathLocator dirLocator = PathLocators.fromLibDirs("foo1");
    PathLocator otherLocator = (comp, path) -> null;

    // be explicit about which equals is being used
    assertFalse(dirLocator.equals(otherLocator));
  }

  @Test
  public void libPath() {
    PathLocator dirLocator1 = PathLocators.fromLibPathString("foo:bar:baz");
    PathLocator dirLocator2 = PathLocators.fromLibDirs("foo", "bar", "baz");

    assertEqualsAndHashCode(dirLocator1, dirLocator2);
  }

  @Test
  public void classLoaderBased_equals() {
    ClassLoader loader = ClassLoader.getSystemClassLoader();

    PathLocator locator1 = PathLocators.fromClassLoader(loader);
    PathLocator locator2 = PathLocators.fromClassLoader(loader);

    assertEqualsAndHashCode(locator1, locator2);
  }

  @Test
  public void classLoaderBased_with_resource_equals() {
    ClassLoader loader = ClassLoader.getSystemClassLoader();

    PathLocator locator1 = PathLocators.fromClassLoader(loader, "resource");
    PathLocator locator2 = PathLocators.fromClassLoader(loader, "resource");

    assertEqualsAndHashCode(locator1, locator2);
  }

  @Test
  public void classLoaderBased_notEequals_loader() {
    ClassLoader loader1 = ClassLoader.getSystemClassLoader();
    ClassLoader loader2 = new URLClassLoader(new URL[0]);

    PathLocator locator1 = PathLocators.fromClassLoader(loader1);
    PathLocator locator2 = PathLocators.fromClassLoader(loader2);

    assertNotEquals(locator1, locator2);
  }

  @Test
  public void classLoaderBased_notEequals_resource() {
    ClassLoader loader = ClassLoader.getSystemClassLoader();

    PathLocator locator1 = PathLocators.fromClassLoader(loader, "resource1");
    PathLocator locator2 = PathLocators.fromClassLoader(loader, "resource2");

    assertNotEquals(locator1, locator2);
  }

  @Test
  public void classLoaderBased_diffType_notEequals() {
    ClassLoader loader = ClassLoader.getSystemClassLoader();

    PathLocator locator1 = PathLocators.fromClassLoader(loader, "resource1");
    PathLocator locator2 = (comp, path) -> null;

    // be explicit about which equals is being used
    assertFalse(locator1.equals(locator2));
  }

  static final void assertEqualsAndHashCode(Object lhs, Object rhs) {
    assertEquals(lhs.hashCode(), rhs.hashCode());
    assertTrue(lhs.equals(rhs));
  }
}
