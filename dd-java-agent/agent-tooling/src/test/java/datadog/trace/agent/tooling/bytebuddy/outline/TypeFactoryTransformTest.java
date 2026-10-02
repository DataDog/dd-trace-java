package datadog.trace.agent.tooling.bytebuddy.outline;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TypeFactoryTransformTest {
  private TypeFactory factory;

  public static class Example {
    public String originalField;

    public void originalMethod() {}
  }

  @BeforeEach
  void setUp() {
    TypeFactory.clear();
    factory = new TypeFactory();
    TypeFactory.typeFactory.set(factory);
    factory.beginInstall();
    factory.switchContext(Example.class.getClassLoader());
  }

  @AfterEach
  void tearDown() {
    factory.endTransform();
    factory.endInstall();
    TypeFactory.typeFactory.remove();
    TypeFactory.clear();
  }

  @Test
  void transformBytesOverrideWarmOutlineAndFullCachesWithoutPoisoningOtherLookups()
      throws Exception {
    String name = Example.class.getName();
    byte[] originalBytes = ClassFileLocator.ForClassLoader.read(Example.class);
    TypeDescription original = TypeFactory.findType(name);
    assertTrue(original.getInterfaces().isEmpty());
    factory.enableFullDescriptions();
    assertEquals(1, original.getDeclaredFields().size());
    factory.disableFullDescriptions();

    factory.beginTransform(name, withInterfaceAndField(originalBytes, "observerField"));
    TypeDescription target = TypeFactory.findType(name);
    assertAugmented(target, "observerField");
    factory.enableFullDescriptions();
    assertAugmented(target, "observerField");
    assertAugmented(TypeFactory.findType(name), "observerField");
    factory.endTransform();

    // Original same-loader shared/classpath descriptions are not overwritten by transform bytes.
    TypeDescription outsideTransform = TypeFactory.findType(name);
    assertTrue(outsideTransform.getInterfaces().isEmpty());
    factory.enableFullDescriptions();
    assertEquals(1, outsideTransform.getDeclaredFields().size());
    factory.disableFullDescriptions();

    factory.beginTransform(name, withInterfaceAndField(originalBytes, "nextField"));
    TypeDescription next = TypeFactory.findType(name);
    assertNotSame(target, next);
    assertAugmented(next, "nextField");
    factory.enableFullDescriptions();
    assertAugmented(next, "nextField");
    assertTrue(next.getDeclaredFields().filter(named("observerField")).isEmpty());
    factory.endTransform();

    factory.beginTransform(name, originalBytes);
    TypeDescription restored = TypeFactory.findType(name);
    assertTrue(restored.getInterfaces().isEmpty());
    factory.enableFullDescriptions();
    assertEquals(1, restored.getDeclaredFields().size());
  }

  @Test
  void transformBytesOnAColdCacheAreSharedLikeAnyOtherParse() throws Exception {
    String name = Example.class.getName();
    byte[] augmented =
        withInterfaceAndField(ClassFileLocator.ForClassLoader.read(Example.class), "sharedField");

    factory.beginTransform(name, augmented);
    assertAugmented(TypeFactory.findType(name), "sharedField");
    factory.endTransform();

    // Later lookups, such as supertype walks from other classes, reuse the shared description.
    factory.switchContext(Example.class.getClassLoader());
    assertEquals(
        Serializable.class.getName(),
        TypeFactory.findType(name).getInterfaces().getOnly().asErasure().getName());
  }

  private static void assertAugmented(TypeDescription type, String field) {
    assertEquals(
        Serializable.class.getName(), type.getInterfaces().getOnly().asErasure().getName());
    assertEquals(1, type.getDeclaredFields().filter(named(field)).size());
    assertEquals(1, type.getDeclaredMethods().filter(named("originalMethod")).size());
  }

  private static byte[] withInterfaceAndField(byte[] original, String field) {
    ClassWriter writer = new ClassWriter(0);
    new ClassReader(original)
        .accept(
            new ClassVisitor(Opcodes.ASM9, writer) {
              @Override
              public void visit(
                  int version,
                  int access,
                  String name,
                  String signature,
                  String superName,
                  String[] interfaces) {
                super.visit(
                    version,
                    access,
                    name,
                    signature,
                    superName,
                    new String[] {"java/io/Serializable"});
              }

              @Override
              public void visitEnd() {
                super.visitField(Opcodes.ACC_PUBLIC, field, "Ljava/lang/Object;", null, null)
                    .visitEnd();
                super.visitEnd();
              }
            },
            0);
    return writer.toByteArray();
  }
}
