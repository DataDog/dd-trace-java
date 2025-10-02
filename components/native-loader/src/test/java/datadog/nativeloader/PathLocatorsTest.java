package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.File;
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

    assertEquals(dirLocator1.hashCode(), dirLocator2.hashCode());
    assertEquals(dirLocator1, dirLocator2);

    assertEquals(dirLocator1.hashCode(), dirLocator3.hashCode());
    assertEquals(dirLocator1, dirLocator3);
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
    assertNotEquals(dirLocator, otherLocator);
  }

  @Test
  public void libPath() {
    PathLocator dirLocator1 = PathLocators.fromLibPathString("foo:bar:baz");
    PathLocator dirLocator2 = PathLocators.fromLibDirs("foo", "bar", "baz");

    assertEquals(dirLocator1.hashCode(), dirLocator2.hashCode());
    assertEquals(dirLocator1, dirLocator2);
  }
}
