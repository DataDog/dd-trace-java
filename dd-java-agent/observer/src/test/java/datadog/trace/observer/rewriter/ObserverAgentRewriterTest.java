package datadog.trace.observer.rewriter;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class ObserverAgentRewriterTest {
  private static final Set<String> ROOTS =
      new HashSet<>(asList("datadog/trace", "com/datadog/debugger", "net/bytebuddy"));
  private static final String PROVIDER = "datadog/trace/bootstrap/config/provider/ConfigProvider";

  @Test
  void neverOverwritesTheStockArtifact(@TempDir Path directory) throws IOException {
    File input = directory.resolve("stock.jar").toFile();
    Files.write(input.toPath(), "unchanged".getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class, () -> ObserverAgentRewriter.rewrite(input, input));
    assertEquals(
        "unchanged", new String(Files.readAllBytes(input.toPath()), StandardCharsets.UTF_8));
  }

  @Test
  void refusesAnIncompleteStockLayoutBeforeWritingOutput(@TempDir Path directory)
      throws IOException {
    File input = directory.resolve("stock.jar").toFile();
    File output = directory.resolve("observer.jar").toFile();
    writeJar(input, new LinkedHashMap<>());
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, () -> ObserverAgentRewriter.rewrite(input, output));
    assertTrue(failure.getMessage().contains("Missing expected stock entry"));
    assertFalse(output.exists());
  }

  @Test
  void reportsEveryMissingSeamBeforeWritingOutput(@TempDir Path directory) throws Exception {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    for (String name :
        asList(
            "datadog/trace/bootstrap/AgentBootstrap",
            "datadog/trace/bootstrap/AgentPreCheck",
            PROVIDER,
            "datadog/trace/bootstrap/config/provider/PropertiesConfigSource")) {
      entries.put(name + ".class", emptyClass(name));
    }
    entries.put("dd-java-agent.index", new byte[0]);
    entries.put(
        "inst/instrumenter.index", instrumenterIndex(asList("datadog.trace.instrumentation.a.A")));
    entries.put("inst/known-types.index", new byte[0]);
    File input = directory.resolve("stock.jar").toFile();
    File output = directory.resolve("observer.jar").toFile();
    writeJar(input, entries);
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class, () -> ObserverAgentRewriter.rewrite(input, output));
    assertTrue(failure.getMessage().startsWith("Input agent layout changed"), failure.getMessage());
    assertTrue(
        failure
            .getMessage()
            .contains(PROVIDER + ".createDefault()L" + PROVIDER + ";=expected 1, got 0"));
    assertTrue(failure.getMessage().contains("\"moduleLayout\"=expected 3, got 0"));
    assertFalse(output.exists());
  }

  @Test
  void rewritingTheStockAgentIsDeterministic(@TempDir Path directory) throws Exception {
    File output = directory.resolve("observer.jar").toFile();
    ObserverAgentRewriter.rewrite(new File(System.getProperty("observer.test.stock")), output);
    try (JarFile actual = new JarFile(output);
        JarFile expected = new JarFile(System.getProperty("observer.test.artifact"))) {
      assertEquals(expected.getManifest(), actual.getManifest());
      List<String> names = new ArrayList<>();
      for (JarEntry entry : java.util.Collections.list(expected.entries())) {
        names.add(entry.getName());
        JarEntry rewritten = actual.getJarEntry(entry.getName());
        assertTrue(rewritten != null, entry.getName());
        assertTrue(
            java.util.Arrays.equals(read(expected, entry), read(actual, rewritten)),
            entry.getName());
      }
      assertEquals(names.size(), java.util.Collections.list(actual.entries()).size());
    }
  }

  private static byte[] read(JarFile jar, JarEntry entry) throws IOException {
    try (java.io.InputStream in = jar.getInputStream(entry)) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
        bytes.write(buffer, 0, n);
      }
      return bytes.toByteArray();
    }
  }

  @Test
  void rootsComeFromJarDirectoriesAndKeepTheJavacPluginShared(@TempDir Path directory)
      throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    for (String name :
        asList(
            "inst/datadog/trace/instrumentation/A.classdata",
            "com/datadog/debugger/B.class",
            "inst/net/bytebuddy/C.classdata",
            "datadog/compiler/annotations/SourcePath.class",
            "datadog/version.txt")) {
      entries.put(name, new byte[0]);
    }
    File jar = directory.resolve("stock.jar").toFile();
    writeJar(jar, entries);
    try (JarFile file = new JarFile(jar)) {
      assertEquals(
          new TreeSet<>(asList("com/datadog/debugger", "datadog/trace", "net/bytebuddy")),
          ObserverAgentRewriter.roots(file));
    }
  }

  @Test
  void relocationAlsoRenamesAgentResourcesAndTheContextProtocol() {
    ObserverAgentRewriter rewriter = new ObserverAgentRewriter(ROOTS);
    assertEquals("inst/observer-instrumenter.index", rewriter.relocate("inst/instrumenter.index"));
    assertEquals("dd-observer-agent.index", rewriter.relocate("dd-java-agent.index"));
    assertEquals("__datadogObserverContext$0", rewriter.relocate("__datadogContext$0"));
    assertEquals("java:comp/env/datadog/tags/", rewriter.relocate("java:comp/env/datadog/tags/"));
  }

  @Test
  void retainsTheFullCatalogAndValidatesThePackedIndex() throws IOException {
    List<String> names =
        asList(
            "datadog.trace.instrumentation.junit5.JUnit5Instrumentation",
            "datadog.trace.instrumentation.junit5.JUnit5SkipInstrumentation",
            "datadog.trace.instrumentation.gradle.GradleDaemonLoggingInstrumentation");
    byte[] index = instrumenterIndex(names);
    ObserverAgentRewriter rewriter = new ObserverAgentRewriter(ROOTS);
    byte[] result = rewriter.rewriteInstrumenterIndex(index);
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(result))) {
      assertEquals(names.size(), in.readInt());
      assertEquals(names.size(), in.readInt());
      assertEquals(in.available() - 4, in.readInt());
    }
    String text = new String(result, StandardCharsets.ISO_8859_1);
    assertTrue(
        text.contains("datadog.trace.observer.trace.instrumentation.junit5.JUnit5Instrumentation"));
    assertTrue(text.contains("JUnit5SkipInstrumentation"));
    byte[] trailing = new byte[index.length + 1];
    System.arraycopy(index, 0, trailing, 0, index.length);
    assertThrows(IllegalArgumentException.class, () -> rewriter.rewriteInstrumenterIndex(trailing));
  }

  @Test
  void contextProtocolNamesAreIsolatedInDefinitionsCallsConstantsAndDynamicNames() {
    String owner = "datadog/trace/bootstrap/FieldBackedContextAccessor";
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
    writer.visitField(Opcodes.ACC_PUBLIC, "__datadogContext$0", "Ljava/lang/Object;", null, null);
    MethodVisitor method =
        writer.visitMethod(Opcodes.ACC_PUBLIC, "$get$__datadogContext$", "()V", null, null);
    method.visitCode();
    method.visitLdcInsn("__datadogContext$");
    method.visitInsn(Opcodes.POP);
    method.visitFieldInsn(Opcodes.GETSTATIC, owner, "__datadogContext$0", "Ljava/lang/Object;");
    method.visitInsn(Opcodes.POP);
    method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "$put$__datadogContext$", "()V", false);
    method.visitInvokeDynamicInsn(
        "$get$__datadogContext$",
        "()V",
        new Handle(Opcodes.H_INVOKESTATIC, owner, "$put$__datadogContext$", "()V", false));
    method.visitInsn(Opcodes.RETURN);
    method.visitMaxs(1, 1);
    method.visitEnd();
    ClassNode node = rewrite(writer);
    assertEquals("__datadogObserverContext$0", node.fields.get(0).name);
    MethodNode rewritten = node.methods.get(0);
    assertEquals("$get$__datadogObserverContext$", rewritten.name);
    AbstractInsnNode[] instructions = rewritten.instructions.toArray();
    assertEquals("__datadogObserverContext$", ((LdcInsnNode) instructions[0]).cst);
    assertEquals("__datadogObserverContext$0", ((FieldInsnNode) instructions[2]).name);
    assertEquals("$put$__datadogObserverContext$", ((MethodInsnNode) instructions[4]).name);
    InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instructions[5];
    assertEquals("$get$__datadogObserverContext$", dynamic.name);
    assertEquals("$put$__datadogObserverContext$", dynamic.bsm.getName());
  }

  @Test
  void validatedFactoriesKeepTheirBodies() {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, PROVIDER, null, "java/lang/Object", null);
    MethodVisitor method =
        writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "withPropertiesOverride",
            "(Ljava/util/Properties;)L" + PROVIDER + ";",
            null,
            null);
    method.visitCode();
    method.visitVarInsn(Opcodes.ALOAD, 0);
    method.visitInsn(Opcodes.POP);
    method.visitInsn(Opcodes.ACONST_NULL);
    method.visitInsn(Opcodes.ARETURN);
    method.visitMaxs(1, 1);
    method.visitEnd();
    List<Integer> opcodes = new ArrayList<>();
    for (AbstractInsnNode instruction : rewrite(writer).methods.get(0).instructions) {
      opcodes.add(instruction.getOpcode());
    }
    assertEquals(asList(Opcodes.ALOAD, Opcodes.POP, Opcodes.ACONST_NULL, Opcodes.ARETURN), opcodes);
  }

  private static ClassNode rewrite(ClassWriter writer) {
    writer.visitEnd();
    ClassNode node = new ClassNode();
    new ClassReader(new ObserverAgentRewriter(ROOTS).rewriteClass(writer.toByteArray()))
        .accept(node, 0);
    return node;
  }

  private static byte[] emptyClass(String name) {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
    writer.visitEnd();
    return writer.toByteArray();
  }

  /** Modules that are their own single member, with one target-system override each. */
  private static byte[] instrumenterIndex(List<String> names) throws IOException {
    ByteArrayOutputStream packed = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(packed)) {
      for (String name : names) {
        out.writeByte(name.length());
        out.writeBytes(name);
        out.writeShort(64);
        out.writeByte(1);
        out.writeByte(255);
        out.writeByte(1);
        out.writeByte(6);
        out.writeBytes("Advice");
        out.writeShort(64);
      }
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(names.size());
      out.writeInt(names.size());
      out.writeInt(packed.size());
      out.write(packed.toByteArray());
    }
    return bytes.toByteArray();
  }

  private static void writeJar(File jar, Map<String, byte[]> entries) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("Premain-Class", "datadog.trace.bootstrap.AgentPreCheck");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()), manifest)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        out.putNextEntry(new JarEntry(entry.getKey()));
        out.write(entry.getValue());
        out.closeEntry();
      }
    }
  }
}
