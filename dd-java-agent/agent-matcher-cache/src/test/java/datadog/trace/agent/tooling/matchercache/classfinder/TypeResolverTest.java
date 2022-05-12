package datadog.trace.agent.tooling.matchercache.classfinder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;

public class TypeResolverTest {
  public final String TEST_CLASSES_FOLDER =
      this.getClass().getClassLoader().getResource("test-classes").getFile();

  @Test
  public void test() throws IOException {
    ClassFinder classFinder = new ClassFinder();
    ClassCollection classCollection =
        classFinder.findClassesIn(new File(TEST_CLASSES_FOLDER, "standard-layout"));

    String xyz = "foo.bar.xyz.Xyz";
    ClassData classData = classCollection.findClassData(xyz);
    assertNotNull(classData);

    TypeResolver typeResolver = new TypeResolver(classCollection, 11);
    TypeDescription typeDefinition = typeResolver.typeDescription(xyz);
    assertEquals(xyz, typeDefinition.getName());
    assertThrows(TypeResolver.ClassNotFound.class, () -> typeResolver.typeDescription("foo.Bar"));

    ClassCollection.Builder ccb = new ClassCollection.Builder();
    ccb.addClass(classData.classBytes(11), xyz, "META-INF/versions/14/foo/bar/xyz/Xyz", "xyz.jar");
    ClassCollection classCollection14 = ccb.buildAndReset();
    TypeResolver typeResolver6 = new TypeResolver(classCollection14, 6);
    assertThrows(TypeResolver.VersionNotFound.class, () -> typeResolver6.typeDescription(xyz));
  }
}
