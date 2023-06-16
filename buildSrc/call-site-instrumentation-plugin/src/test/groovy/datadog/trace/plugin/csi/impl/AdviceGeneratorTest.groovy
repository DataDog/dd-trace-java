package datadog.trace.plugin.csi.impl

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import datadog.trace.agent.tooling.csi.CallSite
import datadog.trace.agent.tooling.csi.CallSites
import datadog.trace.plugin.csi.AdviceGenerator
import groovy.transform.CompileDynamic
import org.objectweb.asm.Type
import spock.lang.Requires
import spock.lang.TempDir

import javax.servlet.ServletRequest
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.security.MessageDigest

import static CallSiteFactory.pointcutParser
import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType

@CompileDynamic
final class AdviceGeneratorTest extends BaseCsiPluginTest {

  @TempDir
  private File buildDir

  @CallSite(spi = CallSites)
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
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(BeforeAdvice)
      advices(0) {
        pointcut('java/security/MessageDigest', 'getInstance', '(Ljava/lang/String;)Ljava/security/MessageDigest;')
        statements(
          'handler.dupParameters(descriptor, StackDupMode.COPY);',
          'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$BeforeAdvice", "before", "(Ljava/lang/String;)V", false);',
          'handler.method(opcode, owner, name, descriptor, isInterface);'
        )
      }
    }
  }

  @CallSite(spi = CallSites)
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
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(AroundAdvice)
      advices(0) {
        pointcut('java/lang/String', 'replaceAll', '(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;')
        statements(
          'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AroundAdvice", "around", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);'
        )
      }
    }
  }

  @CallSite(spi = CallSites)
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
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(AfterAdvice)
      advices(0) {
        pointcut('java/lang/String', 'concat', '(Ljava/lang/String;)Ljava/lang/String;')
        statements(
          'handler.dupInvoke(owner, descriptor, StackDupMode.COPY);',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
          'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AfterAdvice", "after", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);',
        )
      }
    }
  }

  @CallSite(spi = CallSites)
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
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(AfterAdviceCtor)
      advices(0) {
        pointcut('java/net/URL', '<init>', '(Ljava/lang/String;)V')
        statements(
          'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY_CTOR);',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
          'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$AfterAdviceCtor", "after", "([Ljava/lang/Object;Ljava/net/URL;)Ljava/net/URL;", false);',
        )
      }
    }
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

    assertCallSites(result.file) {
      interfaces(CallSites, SpiAdvice.SampleSpi)
    }
  }

  @CallSite(spi = CallSites)
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
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(InvokeDynamicAfterAdvice)
      advices(0) {
        pointcut(
          'java/lang/invoke/StringConcatFactory',
          'makeConcatWithConstants',
          '(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;'
        )
        statements(
          'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY);',
          'handler.invokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);',
          'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicAfterAdvice", "after", "([Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;", false);'
        )
      }
    }
  }

  @CallSite(spi = CallSites)
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
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(InvokeDynamicAroundAdvice)
      advices(0) {
        pointcut(
          'java/lang/invoke/StringConcatFactory',
          'makeConcatWithConstants',
          '(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;'
        )
        statements(
          'handler.invokeDynamic(name, descriptor, new Handle(Opcodes.H_INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicAroundAdvice", "around", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false), bootstrapMethodArguments);',
        )
      }
    }
  }

  @CallSite(spi = CallSites)
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
    assertCallSites(result.file) {
      interfaces(CallSites)
      helpers(InvokeDynamicWithConstantsAdvice)
      advices(0) {
        pointcut(
          'java/lang/invoke/StringConcatFactory',
          'makeConcatWithConstants',
          '(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;'
        )
        statements(
          'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY);',
          'handler.invokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);',
          'handler.loadConstantArray(bootstrapMethodArguments);',
          'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$InvokeDynamicWithConstantsAdvice", "after", "([Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", false);'
        )
      }
    }
  }

  @CallSite(spi = CallSites)
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
    assertCallSites(result.file) {
      advices(0) {
        pointcut('javax/servlet/ServletRequest', 'getParameterMap', '()Ljava/util/Map;')
      }
      advices(1) {
        pointcut('javax/servlet/ServletRequestWrapper', 'getParameterMap', '()Ljava/util/Map;')
      }
    }
  }

  class MinJavaVersionCheck {
    static boolean isAtLeast(final String version) {
      return Integer.parseInt(version) >= 9
    }
  }

  @CallSite(spi = CallSites, enabled = ['datadog.trace.plugin.csi.impl.AdviceGeneratorTest$MinJavaVersionCheck', 'isAtLeast', '18'])
  class MinJavaVersionAdvice {
    @CallSite.After('java.lang.String java.lang.String.concat(java.lang.String)')
    static String after(@CallSite.This final String self, @CallSite.Argument final String param, @CallSite.Return final String result) {
      return result
    }
  }

  void 'test custom enabled property'() {
    setup:
    final spec = buildClassSpecification(MinJavaVersionAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors(result)
    assertCallSites(result.file) { callSites ->
      interfaces(CallSites, CallSites.HasEnabledProperty)
      enabled(MinJavaVersionCheck.getDeclaredMethod('isAtLeast', String), '18')
    }
  }


  @CallSite(spi = CallSites)
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
    final spec = buildClassSpecification(PartialArgumentsBeforeAdvice)
    final generator = buildAdviceGenerator(buildDir)

    when:
    final result = generator.generate(spec)

    then:
    assertNoErrors result
    assertCallSites(result.file) {
      advices(0) {
        pointcut('java/sql/Statement', 'executeUpdate', '(Ljava/lang/String;[Ljava/lang/String;)I')
        statements(
          'int[] parameterIndices = new int[] { 0 };',
          'handler.dupParameters(descriptor, parameterIndices, owner);',
          'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice", "before", "(Ljava/lang/String;)V", false);',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
        )
      }
      advices(1) {
        pointcut('java/lang/String', 'format', '(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;')
        statements(
          'int[] parameterIndices = new int[] { 1 };',
          'handler.dupParameters(descriptor, parameterIndices, null);',
          'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice", "before", "([Ljava/lang/Object;)V", false);',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
        )
      }
      advices(2) {
        pointcut('java/lang/String', 'subSequence', '(II)Ljava/lang/CharSequence;')
        statements(
          'int[] parameterIndices = new int[] { 0 };',
          'handler.dupInvoke(owner, descriptor, parameterIndices);',
          'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$PartialArgumentsBeforeAdvice", "before", "(Ljava/lang/String;I)V", false);',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
        )
      }
    }
  }


  @CallSite(spi = CallSites)
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
    assertNoErrors result
    assertCallSites(result.file) {
      advices(0) {
        pointcut('java/lang/StringBuilder', '<init>', '(Ljava/lang/String;)V')
        statements(
          'handler.dupParameters(descriptor, StackDupMode.PREPEND_ARRAY_CTOR);',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
          'handler.method(Opcodes.INVOKESTATIC, "datadog/trace/plugin/csi/impl/AdviceGeneratorTest$SuperTypeReturnAdvice", "after", "([Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);',
          'handler.instruction(Opcodes.CHECKCAST, "java/lang/StringBuilder");'
        )
      }
    }
  }

  private static AdviceGenerator buildAdviceGenerator(final File targetFolder) {
    return new AdviceGeneratorImpl(targetFolder, pointcutParser())
  }

  private static CompilationUnit parseJavaFile(final File file)
    throws FileNotFoundException {
    final JavaSymbolSolver solver = new JavaSymbolSolver(typeResolver());
    final JavaParser parser = new JavaParser(new ParserConfiguration().setSymbolResolver(solver));
    return parser.parse(file).getResult().get();
  }

  private static Map<String, MethodDeclaration> groupMethods(final TypeDeclaration<?> classNode) {
    return classNode.methods.groupBy { it.name.asString() }
      .collectEntries { key, value -> [key, value.get(0)] }
  }

  private static List<Class<?>> getImplementedTypes(final TypeDeclaration<?> type) {
    return type.asClassOrInterfaceDeclaration().implementedTypes.collect {
      final resolved = it.asClassOrInterfaceType().resolve()
      return resolved.typeDeclaration.get().clazz
    }
  }

  private static Executable resolveMethod(final MethodCallExpr methodCallExpr) {
    final resolved = methodCallExpr.resolve()
    return resolved.@method as Method
  }

  private static List<MethodCallExpr> getMethodCalls(final MethodDeclaration method) {
    return method.body.get().statements.findAll {
      it.isExpressionStmt() && it.asExpressionStmt().expression.isMethodCallExpr()
    }.collect {
      it.asExpressionStmt().expression.asMethodCallExpr()
    }
  }

  private static void assertCallSites(final File generated, @DelegatesTo(CallSiteAssert) final Closure closure) {
    final asserter = buildAsserter(generated)
    closure.delegate = asserter
    closure(asserter)
  }

  private static CallSiteAssert buildAsserter(final File file) {
    final javaFile = parseJavaFile(file)
    assert javaFile.parsed == Node.Parsedness.PARSED
    final adviceClass = javaFile.primaryType.get()
    final interfaces = getImplementedTypes(adviceClass)
    final methods = groupMethods(adviceClass)
    Executable enabled = null
    List<String> enabledArgs = null
    if (interfaces.contains(CallSites.HasEnabledProperty)) {
      final isEnabled = methods['isEnabled']
      final returnStatement = isEnabled.body.get().statements.first.get().asReturnStmt()
      final enabledMethodCall = returnStatement.expression.get().asMethodCallExpr()
      enabled = resolveMethod(enabledMethodCall)
      enabledArgs = enabledMethodCall.getArguments().collect { it.asStringLiteralExpr().asString() }
    }
    final accept = methods['accept']
    final methodCalls = getMethodCalls(accept)
    final addHelpers = methodCalls.find { it.name.toString() == 'addHelpers' }
    assert addHelpers.scope.get().toString() == 'container'
    assert addHelpers.name.toString() == 'addHelpers'
    final helpers = addHelpers.getArguments().collect { typeResolver().resolveType(classNameToType(it.asStringLiteralExpr().asString())) }
    final addAdvices = methodCalls.findAll { it.name.toString() == 'addAdvice' }
    final advices = addAdvices.collect {
      def (owner, method, descriptor) =  it.arguments.subList(0, 3)*.asStringLiteralExpr()*.asString()
      final handlerLambda = it.arguments[3].asLambdaExpr()
      final advice = handlerLambda.body.asBlockStmt().statements*.toString()
      return new AdviceAssert([
        owner     : owner,
        method    : method,
        descriptor: descriptor,
        statements: advice
      ])
    }
    return new CallSiteAssert([
      interfaces : interfaces,
      helpers    : helpers,
      advices    : advices,
      enabled    : enabled,
      enabledArgs: enabledArgs
    ])
  }

  static class CallSiteAssert {
    private Collection<Class<?>> interfaces
    private Collection<Class<?>> helpers
    private Collection<AdviceAssert> advices
    private Method enabled
    private Collection<String> enabledArgs

    void interfaces(Class<?>... values) {
      assert values.toList() == interfaces
    }

    void helpers(Class<?>... values) {
      assert values.toList() == helpers
    }

    void advices(int index, @DelegatesTo(AdviceAssert) Closure closure) {
      final asserter = advices[index]
      closure.delegate = asserter
      closure(asserter)
    }

    void enabled(Method method, String... args) {
      assert method == enabled
      assert args.toList() == enabledArgs
    }
  }

  static class AdviceAssert {
    private String owner
    private String method
    private String descriptor
    private Collection<String> statements

    void pointcut(String owner, String method, String descriptor) {
      assert owner == this.owner
      assert method == this.method
      assert descriptor == this.descriptor
    }

    void statements(String... values) {
      assert values.toList() == statements
    }
  }
}
