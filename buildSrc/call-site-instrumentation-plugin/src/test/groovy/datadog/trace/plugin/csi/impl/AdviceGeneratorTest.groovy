package datadog.trace.plugin.csi.impl

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.type.ClassOrInterfaceType
import datadog.trace.agent.tooling.csi.CallSite
import datadog.trace.plugin.csi.AdviceGenerator
import datadog.trace.plugin.csi.AdviceGenerator.AdviceResult
import datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult
import groovy.transform.CompileDynamic
import spock.lang.Requires
import spock.lang.TempDir

import javax.servlet.ServletRequest
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

import static CallSiteFactory.pointcutParser

@CompileDynamic
final class AdviceGeneratorTest extends BaseCsiPluginTest {

  @TempDir
  private File buildDir

  @CallSite
  class BeforeAdvice {
    @CallSite.Before('java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)')
    static void before(@CallSite.Argument final String algorithm) {}
  }

  void 'test before advice'() {
    setup:
    final spec = buildClassSpecification(BeforeAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'before')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == BeforeAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(BeforeAdvice.simpleName + 'Before')
    final interfaces = getImplementedTypes(adviceClass)
    interfaces.containsAll(['CallSiteAdvice', 'Pointcut', 'InvokeAdvice', 'HasFlags', 'HasHelpers'])
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/security/MessageDigest";']
    getStatements(methods['method']) == ['return "getInstance";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;)Ljava/security/MessageDigest;";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + BeforeAdvice.name + '" };']
    getStatements(methods['flags']) == ['return COMPUTE_MAX_STACK;']
    getStatements(methods['apply']) == [
      'handler.dupParameters(descriptor, StackDupMode.COPY);',
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$BeforeAdvice", "before", "(Ljava/lang/String;)V", false);',
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

  void 'test around advice'() {
    setup:
    final spec = buildClassSpecification(AroundAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'around')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == AroundAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(AroundAdvice.simpleName + 'Around')
    final interfaces = getImplementedTypes(adviceClass)
    interfaces.containsAll(['CallSiteAdvice', 'Pointcut', 'InvokeAdvice', 'HasHelpers'])
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/lang/String";']
    getStatements(methods['method']) == ['return "replaceAll";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + AroundAdvice.name + '" };']
    getStatements(methods['apply']) == [
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AroundAdvice", "around", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);'
    ]
  }

  @CallSite
  class AfterAdvice {
    @CallSite.After('java.lang.String java.lang.String.concat(java.lang.String)')
    static String after(@CallSite.This final String self, @CallSite.Argument final String param, @CallSite.Return final String result) {
      return result
    }
  }

  void 'test after advice'() {
    setup:
    final spec = buildClassSpecification(AfterAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'after')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == AfterAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(AfterAdvice.simpleName + 'After')
    final interfaces = getImplementedTypes(adviceClass)
    interfaces.containsAll(['CallSiteAdvice', 'Pointcut', 'InvokeAdvice', 'HasFlags', 'HasHelpers'])
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/lang/String";']
    getStatements(methods['method']) == ['return "concat";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;)Ljava/lang/String;";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + AfterAdvice.name + '" };']
    getStatements(methods['flags']) == ['return COMPUTE_MAX_STACK;']
    getStatements(methods['apply']) == [
      'handler.dupInvoke(owner, descriptor, StackDupMode.COPY);',
      'handler.method(opcode, owner, name, descriptor, isInterface);',
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AfterAdvice", "after", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);',
    ]
  }

  @CallSite
  class AfterAdviceCtor {
    @CallSite.After('void java.net.URL.<init>(java.lang.String)')
    static URL after(@CallSite.AllArguments final Object[] args, @CallSite.Return final URL url) {
      return url
    }
  }

  void 'test after advice ctor'() {
    setup:
    final spec = buildClassSpecification(AfterAdviceCtor)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'after')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == AfterAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(AfterAdviceCtor.simpleName + 'After')
    final interfaces = getImplementedTypes(adviceClass)
    interfaces.containsAll(['CallSiteAdvice', 'Pointcut', 'InvokeAdvice', 'HasFlags', 'HasHelpers'])
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/net/URL";']
    getStatements(methods['method']) == ['return "<init>";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;)V";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + AfterAdviceCtor.name + '" };']
    getStatements(methods['flags']) == ['return COMPUTE_MAX_STACK;']
    getStatements(methods['apply']) == [
      'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY_CTOR);',
      'handler.method(opcode, owner, name, descriptor, isInterface);',
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AfterAdviceCtor", "after", "([Ljava/lang/Object;Ljava/net/URL;)Ljava/net/URL;", false);',
    ]
  }

  @CallSite(spi = SampleSpi.class)
  class SpiAdvice {
    @CallSite.Before('java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)')
    static void before(@CallSite.Argument final String algorithm) {}
    interface SampleSpi {}
  }

  void 'test generator with spi'() {
    setup:
    final spec = buildClassSpecification(SpiAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'before')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    final adviceClass = javaFile.getType(0)
    final autoService = adviceClass.annotations.find { it.nameAsString.endsWith('AutoService') } as NormalAnnotationExpr
    final value = autoService.pairs.find { it.nameAsString == 'value' }.value as ClassExpr
    final spiType = value.type as ClassOrInterfaceType
    spiType.toString() == 'AdviceGeneratorTest.SpiAdvice.SampleSpi'
  }

  @CallSite
  class InvokeDynamicAfterAdvice {
    @CallSite.After(
      value = 'java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])',
      invokeDynamic = true
    )
    static String after(@CallSite.AllArguments final Object[] arguments, @CallSite.Return final String result) {
      result
    }
  }

  @Requires({
    jvm.java9Compatible
  })
  void 'test invoke dynamic after advice'() {
    setup:
    final spec = buildClassSpecification(InvokeDynamicAfterAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'after')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == InvokeDynamicAfterAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(InvokeDynamicAfterAdvice.simpleName + 'After')
    final interfaces = getImplementedTypes(adviceClass)
    interfaces.containsAll(['CallSiteAdvice', 'Pointcut', 'InvokeDynamicAdvice', 'HasFlags', 'HasHelpers', 'HasMinJavaVersion'])
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
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicAfterAdvice", "after", "([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;", false);'
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
  void 'test invoke dynamic around advice'() {
    setup:
    final spec = buildClassSpecification(InvokeDynamicAroundAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'around')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == InvokeDynamicAroundAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(InvokeDynamicAroundAdvice.simpleName + 'Around')
    final interfaces = getImplementedTypes(adviceClass)
    interfaces.containsAll(['CallSiteAdvice', 'Pointcut', 'InvokeDynamicAdvice', 'HasHelpers', 'HasMinJavaVersion'])
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/lang/invoke/StringConcatFactory";']
    getStatements(methods['method']) == ['return "makeConcatWithConstants";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";']
    getStatements(methods['helperClassNames']) == ['return new String[] { "' + InvokeDynamicAroundAdvice.name + '" };']
    getStatements(methods['apply']) == [
      'handler.invokeDynamic(name, descriptor, new Handle(Opcodes.H_INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicAroundAdvice", "around", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false), bootstrapMethodArguments);',
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
  void 'test invoke dynamic with constants advice'() {
    setup:
    final spec = buildClassSpecification(InvokeDynamicWithConstantsAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'after')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    final packageDcl = javaFile.getPackageDeclaration().get()
    packageDcl.name.asString() == InvokeDynamicWithConstantsAdvice.package.name
    final adviceClass = javaFile.getType(0)
    adviceClass.name.asString().endsWith(InvokeDynamicWithConstantsAdvice.simpleName + 'After')
    final interfaces = getImplementedTypes(adviceClass)
    interfaces.containsAll(['CallSiteAdvice', 'Pointcut', 'InvokeDynamicAdvice', 'HasFlags', 'HasHelpers', 'HasMinJavaVersion'])
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
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicWithConstantsAdvice", "after", "([Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", false);'
    ]
  }

  @CallSite
  class SameMethodNameAdvice {
    @CallSite.Before('java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)')
    static void before(@CallSite.Argument final String algorithm) {}
    @CallSite.Before('java.security.MessageDigest java.security.MessageDigest.getInstance(java.lang.String)')
    static void before() {}
  }

  void 'test multiple methods with the same name advice'() {
    setup:
    final spec = buildClassSpecification(SameMethodNameAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advices = result.advices.collect { it.file.name }
    advices.containsAll(['AdviceGeneratorTest$SameMethodNameAdviceBefore0.java', 'AdviceGeneratorTest$SameMethodNameAdviceBefore1.java'])
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

  void 'test array advice'() {
    setup:
    final spec = buildClassSpecification(ArrayAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advices = result.advices.collect { it.file.name }
    advices.containsAll(['AdviceGeneratorTest$ArrayAdviceAfter0.java', 'AdviceGeneratorTest$ArrayAdviceAfter1.java'])
  }

  @CallSite(minJavaVersion = 9)
  class MinJavaVersionAdvice {
    @CallSite.After('java.lang.String java.lang.String.concat(java.lang.String)')
    static String after(@CallSite.This final String self, @CallSite.Argument final String param, @CallSite.Return final String result) {
      return result
    }
  }

  void 'test min java version advice'() {
    setup:
    final spec = buildClassSpecification(MinJavaVersionAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'after')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    final adviceClass = javaFile.getType(0)
    final interfaces = getImplementedTypes(adviceClass)
    interfaces.containsAll(['CallSiteAdvice', 'Pointcut', 'InvokeAdvice', 'HasFlags', 'HasHelpers', 'HasMinJavaVersion'])
    final methods = groupMethods(adviceClass)
    getStatements(methods['minJavaVersion']) == ['return 9;']
  }


  @CallSite
  class PartialArgumentsBeforeAdvice {
    @CallSite.Before("int java.sql.Statement.executeUpdate(java.lang.String, java.lang.String[])")
    static void before(@CallSite.Argument(0) String arg1) {}

    @CallSite.Before("java.lang.String java.lang.String.format(java.lang.String, java.lang.Object[])")
    static void before(@CallSite.Argument(1) Object[] arg) {}

    @CallSite.Before("java.lang.CharSequence java.lang.String.subSequence(int, int)")
    static void before(@CallSite.This String thiz, @CallSite.Argument(0) int arg) {}
  }

  void 'partial arguments with before advice'() {
    setup:
    CallSiteSpecification spec = buildClassSpecification(PartialArgumentsBeforeAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    CallSiteResult result = generator.generate(spec)

    then:
    assertNoErrors result
    List<String> adviceFiles = result.advices*.file*.name
    adviceFiles.containsAll([
      'AdviceGeneratorTest$PartialArgumentsBeforeAdviceBefore0.java',
      'AdviceGeneratorTest$PartialArgumentsBeforeAdviceBefore1.java',
      'AdviceGeneratorTest$PartialArgumentsBeforeAdviceBefore2.java',
    ])

    when:
    def javaFile = new JavaParser().parse(result.advices.first().file).result.get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    def adviceClass = javaFile.getType(0)
    def methods = groupMethods(adviceClass)

    then:
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/sql/Statement";']
    getStatements(methods['method']) == ['return "executeUpdate";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;[Ljava/lang/String;)I";']
    getStatements(methods['flags']) == ['return COMPUTE_MAX_STACK;']
    getStatements(methods['apply']) == [
      'int[] parameterIndices = new int[] { 0 };',
      'handler.dupParameters(descriptor, parameterIndices, owner);',
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice", "before", "(Ljava/lang/String;)V", false);',
      'handler.method(opcode, owner, name, descriptor, isInterface);',
    ]

    when:
    javaFile = new JavaParser().parse(result.advices*.file[1]).result.get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    adviceClass = javaFile.getType(0)
    methods = groupMethods(adviceClass)

    then:
    getStatements(methods['apply']) == [
      'int[] parameterIndices = new int[] { 1 };',
      'handler.dupParameters(descriptor, parameterIndices, null);',
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice", "before", "([Ljava/lang/Object;)V", false);',
      'handler.method(opcode, owner, name, descriptor, isInterface);',
    ]

    when:
    javaFile = new JavaParser().parse(result.advices*.file[2]).result.get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    adviceClass = javaFile.getType(0)
    methods = groupMethods(adviceClass)

    then:
    getStatements(methods['apply']) == [
      'int[] parameterIndices = new int[] { 0 };',
      'handler.dupInvoke(owner, descriptor, parameterIndices);',
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice", "before", "(Ljava/lang/String;I)V", false);',
      'handler.method(opcode, owner, name, descriptor, isInterface);',
    ]
  }

  @CallSite
  class SuperTypeReturnAdvice {
    @CallSite.After("void java.lang.StringBuilder.<init>(java.lang.String)")
    static Object after(@CallSite.AllArguments Object[] args, @CallSite.Return Object result) {
      return result
    }
  }

  void 'test returning super type'() {
    setup:
    final spec = buildClassSpecification(SuperTypeReturnAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    final advice = findAdvice(result, 'after')
    assertNoErrors(advice)
    final javaFile = new JavaParser().parse(advice.file).getResult().get()
    assert javaFile.parsed == Node.Parsedness.PARSED
    final adviceClass = javaFile.getType(0)
    final methods = groupMethods(adviceClass)
    getStatements(methods['pointcut']) == ['return this;']
    getStatements(methods['type']) == ['return "java/lang/StringBuilder";']
    getStatements(methods['method']) == ['return "<init>";']
    getStatements(methods['descriptor']) == ['return "(Ljava/lang/String;)V";']
    getStatements(methods['apply']) == [
      'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY_CTOR);',
      'handler.method(opcode, owner, name, descriptor, isInterface);',
      'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$SuperTypeReturnAdvice", "after", "([Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);',
      'handler.instruction(Opcodes.CHECKCAST, "java/lang/StringBuilder");'
    ]
  }

  private static List<String> getImplementedTypes(final TypeDeclaration<?> type) {
    return type.asClassOrInterfaceDeclaration().implementedTypes*.nameAsString
  }

  private static List<String> getStatements(final MethodDeclaration method) {
    return method.body.get().statements*.toString()
  }

  private static AdviceGenerator buildAdviceGenerator(final File targetFolder) {
    return new AdviceGeneratorImpl(targetFolder, pointcutParser())
  }

  private static Map<String, MethodDeclaration> groupMethods(final TypeDeclaration<?> classNode) {
    return classNode.methods.groupBy { it.name.asString() }
      .collectEntries { key, value -> [key, value.get(0)] }
  }

  private static AdviceResult findAdvice(final CallSiteResult result, final String name) {
    return result.advices.find { it.specification.advice.methodName == name }
  }
}
