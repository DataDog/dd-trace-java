package datadog.trace.plugin.csi.impl

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import datadog.trace.plugin.csi.samples.AfterAdvice
import datadog.trace.plugin.csi.samples.AroundAdvice
import datadog.trace.plugin.csi.samples.BeforeAdvice
import datadog.trace.plugin.csi.samples.EmptyAdvice
import datadog.trace.plugin.csi.samples.NonPublicStaticMethodAdvice
import datadog.trace.plugin.csi.samples.SameMethodNameAdvice
import datadog.trace.plugin.csi.util.ErrorCode
import spock.lang.Specification
import spock.lang.TempDir

import java.util.stream.Collectors

import static CallSiteFactory.pointcutParser
import static CallSiteFactory.specificationBuilder
import static CallSiteFactory.stackHandler

final class FreemarkerAdviceGeneratorTest extends Specification {

  @TempDir
  File buildDir

  def 'test class generator error, call site without advices'() {
    setup:
    final spec = buildClassSpecification(EmptyAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    result.errors.anyMatch { it.errorCode == ErrorCode.CALL_SITE_SHOULD_HAVE_ADVICE_METHODS }
  }

  def 'test class generator error, non public static method'() {
    setup:
    final spec = buildClassSpecification(NonPublicStaticMethodAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    final advice = result.advices.findFirst().get()
    advice.errors.anyMatch { it.errorCode == ErrorCode.ADVICE_METHOD_NOT_STATIC_AND_PUBLIC }
  }

  def 'test before advice'() {
    setup:
    final spec = buildClassSpecification(BeforeAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    result.errors.count() == 0
    final advice = result.advices.filter { it.specification.advice.methodName == 'beforeMessageDigestGetInstance' }.findFirst().get()
    advice.errors.count() == 0
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == 'datadog.trace.plugin.csi.samples'
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString() == 'BeforeAdviceBeforeMessageDigestGetInstance'
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/security/MessageDigest";']
    getStatements(methods['method']) == ['return "getInstance";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;)Ljava/security/MessageDigest;";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + BeforeAdvice.name + '" };']
    getStatements(methods['apply']) == [
      'visitor.visitInsn(Opcodes.DUP);',
      'visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/samples/BeforeAdvice", "beforeMessageDigestGetInstance", "(Ljava/lang/String;)V", false);',
      'visitor.visitMethodInsn(opcode, owner, name, descriptor, isInterface);'
    ]
  }

  def 'test around advice'() {
    setup:
    final spec = buildClassSpecification(AroundAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    result.errors.count() == 0
    final advice = result.advices.filter { it.specification.advice.methodName == 'aroundStringReplaceAll' }.findFirst().get()
    advice.errors.count() == 0
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == 'datadog.trace.plugin.csi.samples'
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString() == 'AroundAdviceAroundStringReplaceAll'
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/lang/String";']
    getStatements(methods['method']) == ['return "replaceAll";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + AroundAdvice.name + '" };']
    getStatements(methods['apply']) == [
      'visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/samples/AroundAdvice", "aroundStringReplaceAll", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);'
    ]
  }

  def 'test after advice'() {
    setup:
    final spec = buildClassSpecification(AfterAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    result.errors.count() == 0
    final advice = result.advices.filter { it.specification.advice.methodName == 'afterUrlConstructor' }.findFirst().get()
    advice.errors.count() == 0
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == 'datadog.trace.plugin.csi.samples'
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString() == 'AfterAdviceAfterUrlConstructor'
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/net/URL";']
    getStatements(methods['method']) == ['return "<init>";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;)V";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + AfterAdvice.name + '" };']
    getStatements(methods['apply']) == [
      'visitor.visitInsn(Opcodes.DUP_X1);',
      'visitor.visitMethodInsn(opcode, owner, name, descriptor, isInterface);',
      'visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/samples/AfterAdvice", "afterUrlConstructor", "(Ljava/net/URL;Ljava/lang/String;)Ljava/net/URL;", false);'
    ]
  }

  def 'test multiple methods with the same name advice'() {
    setup:
    final spec = buildClassSpecification(SameMethodNameAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    result.errors.count() == 0
    final advices = result.advices.map { it.file.name }.collect(Collectors.toList())
    advices.containsAll(['SameMethodNameAdviceBefore0.java', 'SameMethodNameAdviceBefore1.java'])
  }

  private List<String> getStatements(final MethodDeclaration method) {
    return method.body.get().statements.collect { it.toString() }
  }

  private FreemarkerAdviceGenerator buildFreemarkerAdviceGenerator(final File targetFolder) {
    return new FreemarkerAdviceGenerator(targetFolder, pointcutParser(), stackHandler())
  }

  private static File fetchClass(final Class<?> clazz) {
    final resource = clazz.getResource("${clazz.simpleName}.class")
    return new File(resource.path)
  }

  private static CallSiteSpecification buildClassSpecification(final Class<?> clazz) {
    final classFile = fetchClass(clazz)
    return specificationBuilder().build(classFile).get()
  }

  private static Map<String, MethodDeclaration> groupMethods(final TypeDeclaration<?> classNode) {
    return classNode.methods.groupBy { it.name.asString() }
      .collectEntries { key, value -> [key, value.get(0)] }
  }
}
