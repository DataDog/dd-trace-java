package datadog.trace.agent.tooling.bytebuddy.outline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class TypeFactoryTargetBytecodeTest {
  private static final String TYPE_NAME = "example.MultiReleaseType";

  @TempDir Path resources;

  @AfterEach
  void clearTypes() {
    TypeFactory.typeFactory.get().endTransform();
    TypeFactory.typeFactory.get().endInstall();
    TypeFactory.typeFactory.remove();
    TypeFactory.clear();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void transformBytesOverridePreviouslyResolvedResource(boolean fullDescriptions) throws Exception {
    try (URLClassLoader loader = resourceLoader()) {
      TypeFactory factory = TypeFactory.typeFactory.get();
      factory.beginInstall();
      factory.switchContext(loader);
      factory.beginTransform("example.OtherType", new byte[0]);
      assertEquals("java.util.ArrayList", resolveParent(factory, fullDescriptions));
      factory.endTransform();

      // Model a loader whose resource returns the base entry but defines the versioned entry.
      factory.switchContext(loader);
      factory.beginTransform(TYPE_NAME, classBytes("java/util/LinkedList"));
      assertEquals("java.util.LinkedList", resolveParent(factory, fullDescriptions));

      TypeFactory.LazyType target = (TypeFactory.LazyType) TypeFactory.findType(TYPE_NAME);
      TypeDescription description = target.doResolve(true);
      // Evict the target from the small general-purpose cache without resolving these names.
      for (int i = 0; i < 100; i++) {
        TypeFactory.findType("example.OtherType" + i);
      }
      TypeFactory.LazyType repeated = (TypeFactory.LazyType) TypeFactory.findType(TYPE_NAME);
      assertSame(description, repeated.doResolve(true));

      factory.endTransform();
      factory.switchContext(loader);
      factory.beginTransform(TYPE_NAME, classBytes("java/util/HashSet"));
      assertEquals("java.util.HashSet", resolveParent(factory, fullDescriptions));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void previouslyIssuedReferenceReusesTargetDescription(boolean fullDescriptions) throws Exception {
    try (URLClassLoader loader = resourceLoader()) {
      TypeFactory factory = TypeFactory.typeFactory.get();
      factory.beginInstall();
      factory.switchContext(loader);
      TypeFactory.LazyType retained = (TypeFactory.LazyType) TypeFactory.findType(TYPE_NAME);

      factory.beginTransform(TYPE_NAME, classBytes("java/util/LinkedList"));
      if (fullDescriptions) {
        factory.enableFullDescriptions();
      }
      TypeDescription description = retained.doResolve(true);
      assertEquals("java.util.LinkedList", description.getSuperClass().asErasure().getName());
      TypeFactory.LazyType target = (TypeFactory.LazyType) TypeFactory.findType(TYPE_NAME);
      assertSame(description, target.doResolve(true));
    }
  }

  private URLClassLoader resourceLoader() throws IOException {
    Path resource = resources.resolve(TYPE_NAME.replace('.', '/') + ".class");
    Files.createDirectories(resource.getParent());
    Files.write(resource, classBytes("java/util/ArrayList"));
    return new URLClassLoader(new URL[] {resources.toUri().toURL()}, getClass().getClassLoader());
  }

  private static String resolveParent(TypeFactory factory, boolean fullDescriptions) {
    if (fullDescriptions) {
      factory.enableFullDescriptions();
    }
    TypeDescription type = TypeFactory.findType(TYPE_NAME);
    if (fullDescriptions) {
      type.getDeclaredMethods();
    }
    return type.getSuperClass().asErasure().getName();
  }

  private static byte[] classBytes(String parent) {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TYPE_NAME.replace('.', '/'), null, parent, null);
    writer.visitEnd();
    return writer.toByteArray();
  }
}
