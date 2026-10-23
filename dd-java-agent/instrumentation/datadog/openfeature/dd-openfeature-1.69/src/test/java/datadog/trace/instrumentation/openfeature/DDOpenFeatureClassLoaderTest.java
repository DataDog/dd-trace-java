package datadog.trace.instrumentation.openfeature;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.write;
import static net.bytebuddy.jar.asm.Opcodes.ACC_ABSTRACT;
import static net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
import static net.bytebuddy.jar.asm.Opcodes.ACONST_NULL;
import static net.bytebuddy.jar.asm.Opcodes.ARETURN;
import static net.bytebuddy.jar.asm.Opcodes.ASM9;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class DDOpenFeatureClassLoaderTest extends AbstractInstrumentationTest {
  @TempDir Path sdkOverrides;

  @AfterEach
  void unregisterBackend() {
    FeatureFlaggingGateway.register(null);
  }

  @Test
  void injectsIntoAnIsolatedSdkClassLoader() throws Exception {
    final FeatureFlaggingGateway.Backend backend = mock(FeatureFlaggingGateway.Backend.class);
    when(backend.setting("site")).thenReturn("datadoghq.eu");
    FeatureFlaggingGateway.register(backend);

    try (URLClassLoader loader = sdkClassLoader()) {
      final Object bridge = detect(loader);
      assertSame(loader, bridge.getClass().getClassLoader());
      assertEquals(JavaAgentBridge.class.getName(), bridge.getClass().getName());
      assertEquals(
          "datadoghq.eu",
          loader
              .loadClass(AgentBridge.class.getName())
              .getMethod("setting", String.class)
              .invoke(bridge, "site"));
    }
  }

  @TableTest({
    "Scenario              | SPI                 | Method    | Return type       ",
    "settings changed      | AgentBridge         | setting   |                   ",
    "configuration changed | ConfigurationSource | subscribe |                   ",
    "transport changed     | EventTransport      | post      |                   ",
    "metrics changed       | HealthMetrics       | count     |                   ",
    "enrichment changed    | SpanEnricher        | serialId  |                   ",
    "shutdown changed      | ShutdownSource      | subscribe |                   ",
    "return type changed   | AgentBridge         | setting   | Ljava/lang/Object;"
  })
  void keepsTheStandaloneBridgeWhenAnSpiMethodChanges(
      final String spi, final String method, final String returnType) throws Exception {
    rewriteSpi(
        spi,
        bytes -> {
          final ClassWriter writer = new ClassWriter(0);
          new ClassReader(bytes)
              .accept(
                  new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        String[] exceptions) {
                      if (name.equals(method)) {
                        if (returnType == null) {
                          name = "renamed";
                        } else {
                          descriptor =
                              descriptor.substring(0, descriptor.indexOf(')') + 1) + returnType;
                        }
                      }
                      return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }
                  },
                  0);
          return writer.toByteArray();
        });
    FeatureFlaggingGateway.register(mock(FeatureFlaggingGateway.Backend.class));

    try (URLClassLoader loader = sdkClassLoader()) {
      // Implemented methods are not calls, so Muzzle alone accepts this incompatible SPI.
      assertTrue(new DDOpenFeatureInstrumentation().getInstrumentationMuzzle().matches(loader));
      assertSame(
          loader.loadClass(AgentBridge.class.getName()).getField("NONE").get(null), detect(loader));
    }
  }

  @TableTest({
    "Scenario                       | Abstract | Static conflict",
    "new abstract method            | true     | false          ",
    "new default method             | false    | false          ",
    "abstract conflicts with static | true     | true           ",
    "default conflicts with static  | false    | true           "
  })
  void checksNewSpiMethods(final boolean isAbstract, final boolean staticConflict)
      throws Exception {
    rewriteSpi(
        "AgentBridge",
        bytes -> {
          final ClassWriter writer = new ClassWriter(0);
          new ClassReader(bytes)
              .accept(
                  new ClassVisitor(ASM9, writer) {
                    @Override
                    public void visitEnd() {
                      final MethodVisitor method =
                          super.visitMethod(
                              ACC_PUBLIC | (isAbstract ? ACC_ABSTRACT : 0),
                              staticConflict ? "connect" : "additionalMethod",
                              staticConflict
                                  ? "(Lcom/datadog/openfeature/internal/bridge/v1/AgentBridge;)"
                                      + "Lcom/datadog/openfeature/internal/bridge/v1/AgentBridge;"
                                  : "()Ljava/lang/String;",
                              null,
                              null);
                      if (!isAbstract) {
                        method.visitCode();
                        if (staticConflict) {
                          method.visitInsn(ACONST_NULL);
                        } else {
                          method.visitLdcInsn("compatible");
                        }
                        method.visitInsn(ARETURN);
                        method.visitMaxs(1, staticConflict ? 2 : 1);
                      }
                      method.visitEnd();
                      super.visitEnd();
                    }
                  },
                  0);
          return writer.toByteArray();
        });
    FeatureFlaggingGateway.register(mock(FeatureFlaggingGateway.Backend.class));

    try (URLClassLoader loader = sdkClassLoader()) {
      final Class<?> contract = loader.loadClass(AgentBridge.class.getName());
      final Object bridge = detect(loader);
      if (isAbstract || staticConflict) {
        assertSame(contract.getField("NONE").get(null), bridge);
      } else {
        assertEquals(JavaAgentBridge.class.getName(), bridge.getClass().getName());
        assertEquals("compatible", contract.getMethod("additionalMethod").invoke(bridge));
      }
    }
  }

  private URLClassLoader sdkClassLoader() throws IOException {
    return new URLClassLoader(
        new URL[] {
          sdkOverrides.toUri().toURL(),
          AgentBridge.class.getProtectionDomain().getCodeSource().getLocation()
        },
        null);
  }

  private static Object detect(final ClassLoader loader) throws Exception {
    return loader
        .loadClass("com.datadog.openfeature.internal.bridge.v1.AgentBridges")
        .getMethod("detect")
        .invoke(null);
  }

  private void rewriteSpi(final String spi, final UnaryOperator<byte[]> rewrite)
      throws IOException {
    final String resource = "com/datadog/openfeature/internal/bridge/v1/" + spi + ".class";
    final Path target = sdkOverrides.resolve(resource);
    createDirectories(target.getParent());
    try (InputStream input = AgentBridge.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(input);
      write(target, rewrite.apply(input.readAllBytes()));
    }
  }
}
