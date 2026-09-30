package datadog.gradle.plugin.observer

import datadog.trace.observer.bootstrap.ObserverRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class ObserverAgentRewriterTest {
  @Test
  fun `never overwrites the stock artifact`(@TempDir directory: Path) {
    val input = directory.resolve("stock.jar").toFile()
    input.writeText("unchanged")
    assertThrows(IllegalArgumentException::class.java) {
      ObserverAgentRewriter().rewrite(input, input)
    }
    assertEquals("unchanged", input.readText())
  }

  @Test
  fun `refuses an incomplete stock layout before writing output`(@TempDir directory: Path) {
    val input = directory.resolve("stock.jar").toFile()
    val output = directory.resolve("observer.jar").toFile()
    val manifest = Manifest()
    manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
    manifest.mainAttributes.putValue("Premain-Class", "datadog.trace.bootstrap.AgentPreCheck")
    JarOutputStream(input.outputStream(), manifest).use { }
    val failure = assertThrows(IllegalArgumentException::class.java) {
      ObserverAgentRewriter().rewrite(input, output)
    }
    assertTrue(failure.message!!.contains("Missing expected stock entry"))
    assertFalse(output.exists())
  }

  @Test
  fun `relocates bootstrap dependencies reflection and nested resources`() {
    assertEquals("datadog/trace/observer/trace/api/Config", ObserverAgentRewriter.relocate("datadog/trace/api/Config"))
    assertEquals("datadog.trace.observer.com.datadog.Foo", ObserverAgentRewriter.relocate("com.datadog.Foo"))
    assertEquals("datadog.trace.observer.net.bytebuddy.Foo", ObserverAgentRewriter.relocate("net.bytebuddy.Foo"))
    assertEquals("inst/observer-instrumenter.index", ObserverAgentRewriter.relocate("inst/instrumenter.index"))
    assertEquals("org.junit.platform.engine.TestEngine", ObserverAgentRewriter.relocate("org.junit.platform.engine.TestEngine"))
  }

  @Test
  fun `keeps host paths config fragments and wire names unchanged`() {
    listOf(
      "/var/run/datadog/apm.socket",
      "/var/run/datadog/dsd.socket",
      ".inject.datadog.attribute.enabled",
      "datadog.product:",
      "datadog.tracer.stats.collapsed_spans",
      "datadog.dogstatsd.client.bytes_sent",
      "datadog.origin",
    ).forEach { assertEquals(it, ObserverAgentRewriter.relocate(it)) }
    assertEquals("datadog.trace.observer.span.dispatch", ObserverAgentRewriter.relocate("datadog.span.dispatch"))
  }

  @Test
  fun `logger keys relocate target class suffixes once without rewriting values`() {
    val properties = Properties()
    val keys = listOf(
      "datadog.slf4j.simpleLogger.log.datadog.trace.api.Config",
      "datadog.slf4j.simpleLogger.log.net.bytebuddy.ByteBuddy",
      "datadog.slf4j.simpleLogger.log.com.datadog.Foo",
    )
    keys.forEach { properties.setProperty(it, "datadog.value") }
    val translated = ObserverRuntime.loggerProperties(properties)
    keys.forEach { assertEquals("datadog.value", translated.getProperty(ObserverAgentRewriter.relocate(it))) }
    assertEquals(translated, ObserverRuntime.loggerProperties(translated))
  }

  @Test
  fun `retains the full catalog and validates packed index`() {
    val packed = ByteArrayOutputStream()
    val names = listOf("junit5.JUnit5Instrumentation", "junit5.JUnit5SpockInstrumentation", "junit5.JUnit5SkipInstrumentation",
      "gradle.GradleBuildScopeServices_8_10_Instrumentation", "gradle.GradlePluginInjectorInstrumentation",
      "gradle.GradleServiceValidationInstrumentation", "gradle.GradleDaemonLoggingInstrumentation")
    DataOutputStream(packed).use { out ->
      names.forEach { name ->
        val fullName = "datadog.trace.instrumentation.$name"
        out.writeByte(fullName.length)
        out.writeBytes(fullName)
        out.writeShort(64)
        out.writeByte(1) // member target-system overrides
        out.writeByte(255) // self membership
        out.writeByte(1)
        out.writeByte(6)
        out.writeBytes("Advice")
        out.writeShort(64)
      }
    }
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use {
      it.writeInt(names.size)
      it.writeInt(names.size)
      it.writeInt(packed.size())
      it.write(packed.toByteArray())
    }
    val result = ObserverAgentRewriter.rewriteInstrumenterIndex(bytes.toByteArray())
    DataInputStream(result.inputStream()).use {
      assertEquals(names.size, it.readInt())
      assertEquals(names.size, it.readInt())
      assertEquals(it.available() - 4, it.readInt())
    }
    val text = String(result, Charsets.ISO_8859_1)
    assertTrue(text.contains("datadog.trace.observer.trace.instrumentation.junit5.JUnit5Instrumentation"))
    assertTrue(text.contains("JUnit5SkipInstrumentation"))
    assertThrows(IllegalArgumentException::class.java) {
      ObserverAgentRewriter.rewriteInstrumenterIndex(bytes.toByteArray() + 0)
    }
  }

  @Test
  fun `context protocol names are isolated in definitions calls constants and dynamic names`() {
    val writer = ClassWriter(0)
    val owner = "datadog/trace/bootstrap/FieldBackedContextAccessor"
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
    writer.visitField(Opcodes.ACC_PUBLIC, "__datadogContext\$0", "Ljava/lang/Object;", null, null).visitEnd()
    val method = writer.visitMethod(Opcodes.ACC_PUBLIC, "\$get\$__datadogContext\$", "()V", null, null)
    method.visitCode()
    method.visitLdcInsn("__datadogContext\$")
    method.visitInsn(Opcodes.POP)
    method.visitFieldInsn(Opcodes.GETSTATIC, owner, "__datadogContext\$0", "Ljava/lang/Object;")
    method.visitInsn(Opcodes.POP)
    method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "\$put\$__datadogContext\$", "()V", false)
    method.visitInvokeDynamicInsn("\$get\$__datadogContext\$", "()V", org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, owner, "\$put\$__datadogContext\$", "()V", false))
    method.visitInsn(Opcodes.RETURN)
    method.visitMaxs(1, 1)
    method.visitEnd()
    val node = ClassNode()
    ClassReader(ObserverAgentRewriter().rewriteClass(writer.toByteArray())).accept(node, 0)
    assertEquals("__datadogObserverContext\$0", node.fields.single().name)
    assertEquals("\$get\$__datadogObserverContext\$", node.methods.single().name)
    val instructions = node.methods.single().instructions.toArray()
    assertEquals("__datadogObserverContext\$", (instructions[0] as org.objectweb.asm.tree.LdcInsnNode).cst)
    assertEquals("__datadogObserverContext\$0", (instructions[2] as org.objectweb.asm.tree.FieldInsnNode).name)
    assertEquals("\$put\$__datadogObserverContext\$", (instructions[4] as MethodInsnNode).name)
    val dynamic = instructions[5] as org.objectweb.asm.tree.InvokeDynamicInsnNode
    assertEquals("\$get\$__datadogObserverContext\$", dynamic.name)
    assertEquals("\$put\$__datadogObserverContext\$", dynamic.bsm.name)
  }

  @Test
  fun `private config ignores ordinary mutations and preserves JVM facts`() {
    val original = System.getProperty("dd.writer.type")
    try {
      System.setProperty("dd.writer.type", "DDAgentWriter")
      assertNull(ObserverRuntime.getProperty("dd.writer.type"))
      assertNull(ObserverRuntime.getProperties().getProperty("dd.writer.type"))
      assertEquals(System.getProperty("java.version"), ObserverRuntime.getProperty("java.version"))
      assertNull(ObserverRuntime.getenv("DD_API_KEY"))
      assertNull(ObserverRuntime.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"))
      assertNull(ObserverRuntime.getProperty("dd.civisibility.itr.enabled"))
      assertFalse(ObserverRuntime.getBoolean("dd.civisibility.itr.enabled"))
      assertFalse(ObserverRuntime.getBoolean("dd.civisibility.enabled"))
      assertFalse(ObserverRuntime.getBoolean(null))
      assertFalse(ObserverRuntime.getBoolean(""))
      ObserverRuntime.getProperties().setProperty("dd.writer.type", "DDAgentWriter")
      assertNull(ObserverRuntime.getProperty("dd.writer.type"))
    } finally {
      if (original == null) System.clearProperty("dd.writer.type") else System.setProperty("dd.writer.type", original)
    }
  }

  @Test
  fun `private factories retain their bodies and caller overrides`() {
    val writer = ClassWriter(0)
    val provider = "datadog/trace/bootstrap/config/provider/ConfigProvider"
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, provider, null, "java/lang/Object", null)
    writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "([L$provider\$Source;)V", null, null).visitEnd()
    val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "withPropertiesOverride", "(Ljava/util/Properties;)L$provider;", null, null)
    method.visitCode()
    method.visitVarInsn(Opcodes.ALOAD, 0)
    method.visitInsn(Opcodes.POP)
    method.visitInsn(Opcodes.ACONST_NULL)
    method.visitInsn(Opcodes.ARETURN)
    method.visitMaxs(1, 1)
    method.visitEnd()
    writer.visitEnd()
    val node = ClassNode()
    ClassReader(ObserverAgentRewriter().rewriteClass(writer.toByteArray())).accept(node, 0)
    val body = node.methods.single { it.name == "withPropertiesOverride" }.instructions.toArray()
    assertEquals(listOf(Opcodes.ALOAD, Opcodes.POP, Opcodes.ACONST_NULL, Opcodes.ARETURN), body.map { it.opcode })
  }

  @Test
  fun `only the outer Gradle engine launch owns observer listeners`() {
    val engine = StackTraceElement("org.junit.platform.launcher.core.EngineExecutionOrchestrator", "executeEngine", "", 1)
    val gradle = StackTraceElement("org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestDefinitionProcessor\$CollectThenExecuteTestDefinitionConsumer", "processAllTestDefinitions", "", 1)
    assertTrue(ObserverRuntime.isOuterGradleExecution(arrayOf(engine, gradle)))
    assertFalse(ObserverRuntime.isOuterGradleExecution(arrayOf(engine, engine, gradle)))
    assertFalse(ObserverRuntime.isOuterGradleExecution(arrayOf(engine)))
    assertFalse(ObserverRuntime.isOuterGradleExecution(arrayOf(gradle)))
    assertFalse(ObserverRuntime.isOuterGradleExecution(emptyArray()))
  }
}
