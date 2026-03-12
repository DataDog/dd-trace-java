package com.datadog.appsec.config

import datadog.trace.test.util.DDSpecification
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

import java.util.function.Supplier

class AppSecSCATransformerTest extends DDSpecification {

  Supplier<AppSecSCAConfig> configSupplier

  void setup() {
    configSupplier = Mock(Supplier)
  }

  def "constructor creates transformer with config supplier"() {
    when:
    def transformer = new AppSecSCATransformer(configSupplier)

    then:
    transformer != null
  }

  def "transform returns null when className is null"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def classfileBuffer = createSimpleClassBytecode()

    when:
    def result = transformer.transform(null, null, null, null, classfileBuffer)

    then:
    result == null
    0 * configSupplier.get()
  }

  def "transform returns null when config is null"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def classfileBuffer = createSimpleClassBytecode()
    configSupplier.get() >> null

    when:
    def result = transformer.transform(null, "java/lang/String", null, null, classfileBuffer)

    then:
    result == null
  }

  def "transform returns null when config has no vulnerabilities"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def classfileBuffer = createSimpleClassBytecode()
    def config = new AppSecSCAConfig(vulnerabilities: [])
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, "java/lang/String", null, null, classfileBuffer)

    then:
    result == null
  }

  def "transform returns null when config has null vulnerabilities"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def classfileBuffer = createSimpleClassBytecode()
    def config = new AppSecSCAConfig(vulnerabilities: null)
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, "java/lang/String", null, null, classfileBuffer)

    then:
    result == null
  }

  def "transform returns null when class is not a target"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def classfileBuffer = createSimpleClassBytecode()
    def config = createConfigWithOneVulnerability("com.example.VulnerableClass")
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, "java/lang/String", null, null, classfileBuffer)

    then:
    result == null
  }

  def "transform instruments when class is a target with external entrypoint"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def targetClassName = "com/example/VulnerableClass"
    def classfileBuffer = createClassBytecodeWithMethod("vulnerableMethod")
    def config = createConfigWithOneVulnerability("com.example.VulnerableClass")
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, targetClassName, null, null, classfileBuffer)

    then:
    result != null
    result != classfileBuffer // Should return modified bytecode
  }

  def "transform converts internal class name to binary format"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def targetClassName = "com/example/nested/VulnerableClass"
    def classfileBuffer = createClassBytecodeWithMethod("vulnerableMethod")
    // Config uses binary format
    def config = createConfigWithOneVulnerability("com.example.nested.VulnerableClass")
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, targetClassName, null, null, classfileBuffer)

    then:
    result != null
    result != classfileBuffer
  }

  def "transform handles multiple vulnerabilities for same class"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def targetClassName = "com/example/VulnerableClass"
    def classfileBuffer = createClassBytecodeWithMethods(["method1", "method2"])

    // Create config with multiple vulnerabilities targeting the same class
    def entrypoint1 = new AppSecSCAConfig.ExternalEntrypoint(
      className: "com.example.VulnerableClass",
      methods: ["method1"]
      )
    def vulnerability1 = new AppSecSCAConfig.Vulnerability(
      advisory: "GHSA-xxxx-1111-zzzz",
      cve: "CVE-2024-0001",
      externalEntrypoint: entrypoint1
      )

    def entrypoint2 = new AppSecSCAConfig.ExternalEntrypoint(
      className: "com.example.VulnerableClass",
      methods: ["method2"]
      )
    def vulnerability2 = new AppSecSCAConfig.Vulnerability(
      advisory: "GHSA-xxxx-2222-zzzz",
      cve: "CVE-2024-0002",
      externalEntrypoint: entrypoint2
      )

    def config = new AppSecSCAConfig(vulnerabilities: [vulnerability1, vulnerability2])
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, targetClassName, null, null, classfileBuffer)

    then:
    result != null
    result != classfileBuffer
  }

  def "transform handles vulnerability with null advisory"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def targetClassName = "com/example/VulnerableClass"
    def classfileBuffer = createClassBytecodeWithMethod("vulnerableMethod")

    def entrypoint = new AppSecSCAConfig.ExternalEntrypoint(
      className: "com.example.VulnerableClass",
      methods: ["vulnerableMethod"]
      )
    def vulnerability = new AppSecSCAConfig.Vulnerability(
      advisory: null,
      cve: "CVE-2024-0001",
      externalEntrypoint: entrypoint
      )
    def config = new AppSecSCAConfig(vulnerabilities: [vulnerability])
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, targetClassName, null, null, classfileBuffer)

    then:
    result != null
    result != classfileBuffer
  }

  def "transform handles vulnerability with null cve"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def targetClassName = "com/example/VulnerableClass"
    def classfileBuffer = createClassBytecodeWithMethod("vulnerableMethod")

    def entrypoint = new AppSecSCAConfig.ExternalEntrypoint(
      className: "com.example.VulnerableClass",
      methods: ["vulnerableMethod"]
      )
    def vulnerability = new AppSecSCAConfig.Vulnerability(
      advisory: "GHSA-xxxx-yyyy-zzzz",
      cve: null,
      externalEntrypoint: entrypoint
      )
    def config = new AppSecSCAConfig(vulnerabilities: [vulnerability])
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, targetClassName, null, null, classfileBuffer)

    then:
    result != null
    result != classfileBuffer
  }

  def "transform returns null when class bytecode is invalid"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def targetClassName = "com/example/VulnerableClass"
    def invalidBytecode = "invalid bytecode".bytes // Invalid class file
    def config = createConfigWithOneVulnerability("com.example.VulnerableClass")
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, targetClassName, null, null, invalidBytecode)

    then:
    result == null // Should return null on error, not throw
  }

  def "transform handles entrypoint with empty methods list"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def targetClassName = "com/example/VulnerableClass"
    def classfileBuffer = createSimpleClassBytecode()

    def entrypoint = new AppSecSCAConfig.ExternalEntrypoint(
      className: "com.example.VulnerableClass",
      methods: [] // Empty methods list
      )
    def vulnerability = new AppSecSCAConfig.Vulnerability(
      advisory: "GHSA-xxxx-yyyy-zzzz",
      cve: "CVE-2024-0001",
      externalEntrypoint: entrypoint
      )
    def config = new AppSecSCAConfig(vulnerabilities: [vulnerability])
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, targetClassName, null, null, classfileBuffer)

    then:
    result == null // No methods to instrument
  }

  def "transform handles entrypoint with null methods"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def targetClassName = "com/example/VulnerableClass"
    def classfileBuffer = createSimpleClassBytecode()

    def entrypoint = new AppSecSCAConfig.ExternalEntrypoint(
      className: "com.example.VulnerableClass",
      methods: null // Null methods
      )
    def vulnerability = new AppSecSCAConfig.Vulnerability(
      advisory: "GHSA-xxxx-yyyy-zzzz",
      cve: "CVE-2024-0001",
      externalEntrypoint: entrypoint
      )
    def config = new AppSecSCAConfig(vulnerabilities: [vulnerability])
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, targetClassName, null, null, classfileBuffer)

    then:
    result == null // No methods to instrument
  }

  def "transform ignores null or empty method names in methods list"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def targetClassName = "com/example/VulnerableClass"
    def classfileBuffer = createClassBytecodeWithMethod("validMethod")

    def entrypoint = new AppSecSCAConfig.ExternalEntrypoint(
      className: "com.example.VulnerableClass",
      methods: [null, "", "validMethod"] // Mix of invalid and valid
      )
    def vulnerability = new AppSecSCAConfig.Vulnerability(
      advisory: "GHSA-xxxx-yyyy-zzzz",
      cve: "CVE-2024-0001",
      externalEntrypoint: entrypoint
      )
    def config = new AppSecSCAConfig(vulnerabilities: [vulnerability])
    configSupplier.get() >> config

    when:
    def result = transformer.transform(null, targetClassName, null, null, classfileBuffer)

    then:
    result != null // Should still instrument the valid method
    result != classfileBuffer
  }

  def "transform uses dynamic config from supplier on each invocation"() {
    given:
    def transformer = new AppSecSCATransformer(configSupplier)
    def targetClassName = "com/example/VulnerableClass"
    def classfileBuffer = createClassBytecodeWithMethod("vulnerableMethod")

    def config1 = createConfigWithOneVulnerability("com.example.OtherClass")
    def config2 = createConfigWithOneVulnerability("com.example.VulnerableClass")

    // Configure mock to return different configs on consecutive calls
    configSupplier.get() >>> [config1, config2]

    when:
    // First call - config1 doesn't match
    def result1 = transformer.transform(null, targetClassName, null, null, classfileBuffer)

    then:
    result1 == null

    when:
    // Second call - config2 matches
    def result2 = transformer.transform(null, targetClassName, null, null, classfileBuffer)

    then:
    result2 != null
    result2 != classfileBuffer
  }

  // Helper methods

  private AppSecSCAConfig createConfigWithOneVulnerability(String className) {
    def entrypoint = new AppSecSCAConfig.ExternalEntrypoint(
      className: className,
      methods: ["vulnerableMethod"]
      )
    def vulnerability = new AppSecSCAConfig.Vulnerability(
      advisory: "GHSA-xxxx-yyyy-zzzz",
      cve: "CVE-2024-0001",
      externalEntrypoint: entrypoint
      )
    return new AppSecSCAConfig(vulnerabilities: [vulnerability])
  }

  /**
   * Creates a simple valid class bytecode for testing.
   * Equivalent to: public class TestClass { public void testMethod() {} }
   */
  private byte[] createSimpleClassBytecode() {
    ClassWriter cw = new ClassWriter(0)
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "TestClass", null, "java/lang/Object", null)

    // Add constructor
    def mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    mv.visitCode()
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 1)
    mv.visitEnd()

    // Add test method
    mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "testMethod", "()V", null, null)
    mv.visitCode()
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(0, 1)
    mv.visitEnd()

    cw.visitEnd()
    return cw.toByteArray()
  }

  /**
   * Creates class bytecode with a specific method name.
   * Equivalent to: public class TestClass { public void [methodName]() {} }
   */
  private byte[] createClassBytecodeWithMethod(String methodName) {
    return createClassBytecodeWithMethods([methodName])
  }

  /**
   * Creates class bytecode with multiple specific method names.
   * Equivalent to: public class TestClass { public void method1() {} public void method2() {} ... }
   */
  private byte[] createClassBytecodeWithMethods(List<String> methodNames) {
    ClassWriter cw = new ClassWriter(0)
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "TestClass", null, "java/lang/Object", null)

    // Add constructor
    def mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    mv.visitCode()
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 1)
    mv.visitEnd()

    // Add specified methods
    for (String methodName : methodNames) {
      mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null)
      mv.visitCode()
      mv.visitInsn(Opcodes.RETURN)
      mv.visitMaxs(0, 1)
      mv.visitEnd()
    }

    cw.visitEnd()
    return cw.toByteArray()
  }
}
