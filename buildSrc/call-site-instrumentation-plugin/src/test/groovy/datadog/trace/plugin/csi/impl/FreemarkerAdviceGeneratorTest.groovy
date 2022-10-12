package datadog.trace.plugin.csi.impl

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import datadog.trace.agent.tooling.csi.CallSite
import datadog.trace.plugin.csi.AdviceGenerator.AdviceResult
import datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult
import spock.lang.Requires
import spock.lang.TempDir

import javax.servlet.ServletRequest
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.stream.Collectors

import static CallSiteFactory.pointcutParser

final class FreemarkerAdviceGeneratorTest extends BaseCsiPluginTest {

  @TempDir
  File buildDir

  @CallSite
  class BeforeAdvice {
    @CallSite.Before('java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)')
    static void before(@CallSite.Argument final String algorithm) {}
  }

  def 'test before advice'() {
    setup:
    final spec = buildClassSpecification(BeforeAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'before')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == BeforeAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(BeforeAdvice.simpleName + 'Before')
    final interfaces = adviceClass.asClassOrInterfaceDeclaration().implementedTypes.collect {it.name.asString() }
    interfaces == ['CallSiteAdvice', 'Pointcut', 'InvokeAdvice', 'HasFlags', 'HasHelpers']
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/security/MessageDigest";']
    getStatements(methods['method']) == ['return "getInstance";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;)Ljava/security/MessageDigest;";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + BeforeAdvice.name + '" };']
    getStatements(methods['flags']) == ['return COMPUTE_MAX_STACK;']
    getStatements(methods['apply']) == [
      'handler.dupParameters(descriptor, StackDupMode.COPY);',
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/FreemarkerAdviceGeneratorTest$BeforeAdvice", "before", "(Ljava/lang/String;)V", false);',
      'handler.method(opcode, owner, name, descriptor, isInterface);'
    ]
  }

  @CallSite
  class AroundAdvice {
    @CallSite.Around('java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)')
    static String around(@CallSite.This final String self, @CallSite.Argument final String regexp, @CallSite.Argument final String replacement) {
      return self.replaceAll(regexp, replacement);
    }
  }

  def 'test around advice'() {
    setup:
    final spec = buildClassSpecification(AroundAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'around')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == AroundAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(AroundAdvice.simpleName + 'Around')
    final interfaces = adviceClass.asClassOrInterfaceDeclaration().implementedTypes.collect {it.name.asString() }
    interfaces == ['CallSiteAdvice', 'Pointcut', 'InvokeAdvice', 'HasHelpers']
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/lang/String";']
    getStatements(methods['method']) == ['return "replaceAll";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + AroundAdvice.name + '" };']
    getStatements(methods['apply']) == [
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/FreemarkerAdviceGeneratorTest$AroundAdvice", "around", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);'
    ]
  }

  @CallSite
  class AfterAdvice {
    @CallSite.After('void java.net.URL.<init>(java.lang.String)')
    static URL after(@CallSite.This final URL url, @CallSite.Argument final String spec) {
      return url;
    }
  }

  def 'test after advice'() {
    setup:
    final spec = buildClassSpecification(AfterAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'after' )
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == AfterAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(AfterAdvice.simpleName + 'After')
    final interfaces = adviceClass.asClassOrInterfaceDeclaration().implementedTypes.collect {it.name.asString() }
    interfaces == ['CallSiteAdvice', 'Pointcut', 'InvokeAdvice', 'HasFlags', 'HasHelpers']
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/net/URL";']
    getStatements(methods['method']) == ['return "<init>";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;)V";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + AfterAdvice.name + '" };']
    getStatements(methods['flags']) == ['return COMPUTE_MAX_STACK;']
    getStatements(methods['apply']) == [
      'handler.dupInvoke(owner, descriptor, StackDupMode.COPY);',
      'handler.method(opcode, owner, name, descriptor, isInterface);',
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/FreemarkerAdviceGeneratorTest$AfterAdvice", "after", "(Ljava/net/URL;Ljava/lang/String;)Ljava/net/URL;", false);',
      'handler.instruction(Opcodes.POP);'
    ]
  }

  @CallSite(spi = SampleSpi.class)
  class SpiAdvice {
    @CallSite.Before('java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)')
    static void before(@CallSite.Argument final String algorithm) {}
    interface SampleSpi {}
  }

  def 'test generator with spi'() {
    setup:
    final spec = buildClassSpecification(SpiAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'before' )
    assertNoErrors(advice)
    final text = advice.file.text
    text.contains('@AutoService(FreemarkerAdviceGeneratorTest.SpiAdvice.SampleSpi.class)')
  }

  @CallSite
  class InvokeDynamicAfterAdvice {
    @CallSite.After(
      value = 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])',
      invokeDynamic = true
    )
    static String after(@CallSite.AllArguments final Object[] arguments, @CallSite.Return final String result) {
      return result;
    }
  }

  @Requires({
    jvm.java9Compatible
  })
  def 'test invoke dynamic after advice'() {
    setup:
    final spec = buildClassSpecification(InvokeDynamicAfterAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'after' )
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == InvokeDynamicAfterAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(InvokeDynamicAfterAdvice.simpleName + 'After')
    final interfaces = adviceClass.asClassOrInterfaceDeclaration().implementedTypes.collect {it.name.asString() }
    interfaces == ['CallSiteAdvice', 'Pointcut', 'InvokeDynamicAdvice', 'HasFlags', 'HasHelpers']
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/lang/invoke/StringConcatFactory";']
    getStatements(methods['method']) == ['return "makeConcatWithConstants";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + InvokeDynamicAfterAdvice.name + '" };']
    getStatements(methods['flags']) == ['return COMPUTE_MAX_STACK;']
    getStatements(methods['apply']) == [
      'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY);',
      'handler.invokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);',
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/FreemarkerAdviceGeneratorTest$InvokeDynamicAfterAdvice", "after", "([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;", false);'
    ]
  }

  @CallSite
  class InvokeDynamicAroundAdvice {
    @CallSite.Around(
      value = 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])',
      invokeDynamic = true
    )
    static java.lang.invoke.CallSite around(@CallSite.Argument final MethodHandles.Lookup lookup,
                                            @CallSite.Argument final String name,
                                            @CallSite.Argument final MethodType concatType,
                                            @CallSite.Argument final String recipe,
                                            @CallSite.Argument final Object... constants) {
      return null;
    }
  }

  @Requires({
    jvm.java9Compatible
  })
  def 'test invoke dynamic around advice'() {
    setup:
    final spec = buildClassSpecification(InvokeDynamicAroundAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'around' )
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == InvokeDynamicAroundAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(InvokeDynamicAroundAdvice.simpleName + 'Around')
    final interfaces = adviceClass.asClassOrInterfaceDeclaration().implementedTypes.collect {it.name.asString() }
    interfaces == ['CallSiteAdvice', 'Pointcut', 'InvokeDynamicAdvice', 'HasHelpers']
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/lang/invoke/StringConcatFactory";']
    getStatements(methods['method']) == ['return "makeConcatWithConstants";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + InvokeDynamicAroundAdvice.name + '" };']
    getStatements(methods['apply']) == [
      'handler.invokeDynamic(name, descriptor, new Handle(Opcodes.H_INVOKESTATIC, "datadog/trace/plugin/csi/impl/FreemarkerAdviceGeneratorTest$InvokeDynamicAroundAdvice", "around", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false), bootstrapMethodArguments);',
    ]
  }

  @CallSite
  class InvokeDynamicWithConstantsAdvice {
    @CallSite.After(
      value = 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])',
      invokeDynamic = true
    )
    static String after(@CallSite.AllArguments final Object[] arguments,
                        @CallSite.Return final String result,
                        @CallSite.InvokeDynamicConstants final Object[] constants) {
      return result;
    }
  }

  @Requires({
    jvm.java9Compatible
  })
  def 'test invoke dynamic with constants advice'() {
    setup:
    final spec = buildClassSpecification(InvokeDynamicWithConstantsAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'after' )
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == InvokeDynamicWithConstantsAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(InvokeDynamicWithConstantsAdvice.simpleName + 'After')
    final interfaces = adviceClass.asClassOrInterfaceDeclaration().implementedTypes.collect {it.name.asString() }
    interfaces == ['CallSiteAdvice', 'Pointcut', 'InvokeDynamicAdvice', 'HasFlags', 'HasHelpers']
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/lang/invoke/StringConcatFactory";']
    getStatements(methods['method']) == ['return "makeConcatWithConstants";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + InvokeDynamicWithConstantsAdvice.name + '" };']
    getStatements(methods['flags']) == ['return COMPUTE_MAX_STACK;']
    getStatements(methods['apply']) == [
      'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY);',
      'handler.invokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);',
      'handler.loadConstantArray(bootstrapMethodArguments);',
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/FreemarkerAdviceGeneratorTest$InvokeDynamicWithConstantsAdvice", "after", "([Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", false);'
    ]
  }

  @CallSite
  class SameMethodNameAdvice {
    @CallSite.Before('java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)')
    static void before(@CallSite.Argument final String algorithm) {}
    @CallSite.Before('java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)')
    static void before() {}
  }

  def 'test multiple methods with the same name advice'() {
    setup:
    final spec = buildClassSpecification(SameMethodNameAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advices = result.advices.map { it.file.name }.collect(Collectors.toList())
    advices.containsAll(['FreemarkerAdviceGeneratorTest$SameMethodNameAdviceBefore0.java', 'FreemarkerAdviceGeneratorTest$SameMethodNameAdviceBefore1.java'])
  }

  @CallSite
  class ArrayAdvice {
    @CallSite.AfterArray([
      @CallSite.After('java.util.Map javax.servlet.ServletRequest.getParameterMap()'),
      @CallSite.After('java.util.Map javax.servlet.ServletRequestWrapper.getParameterMap()')
    ])
    static Map after(@CallSite.This final ServletRequest request, @CallSite.Return final Map parameters) {
      return parameters
    }
  }

  def 'test array advice'() {
    setup:
    final spec = buildClassSpecification(ArrayAdvice)
    final generator = buildFreemarkerAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advices = result.advices.map { it.file.name }.collect(Collectors.toList())
    advices.containsAll(['FreemarkerAdviceGeneratorTest$ArrayAdviceAfter0.java', 'FreemarkerAdviceGeneratorTest$ArrayAdviceAfter1.java'])
  }

  private static List<String> getStatements(final MethodDeclaration method) {
    return method.body.get().statements.collect { it.toString() }
  }

  private static FreemarkerAdviceGenerator buildFreemarkerAdviceGenerator(final File targetFolder) {
    return new FreemarkerAdviceGenerator(targetFolder, pointcutParser())
  }

  private static Map<String, MethodDeclaration> groupMethods(final TypeDeclaration<?> classNode) {
    return classNode.methods.groupBy { it.name.asString() }
      .collectEntries { key, value -> [key, value.get(0)] }
  }

  private static AdviceResult findAdvice(final CallSiteResult result, final String name) {
    return result.advices.filter { it.specification.advice.methodName == name }.findFirst().get()
  }
}
