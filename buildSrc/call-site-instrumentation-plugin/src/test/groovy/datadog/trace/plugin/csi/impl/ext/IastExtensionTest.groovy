package datadog.trace.plugin.csi.impl.ext

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.type.Type
import com.github.javaparser.resolution.types.ResolvedArrayType
import com.github.javaparser.resolution.types.ResolvedPrimitiveType
import com.github.javaparser.resolution.types.ResolvedVoidType
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import datadog.trace.agent.tooling.csi.CallSites
import datadog.trace.api.iast.csi.HasDynamicSupport
import datadog.trace.plugin.csi.AdviceGenerator
import datadog.trace.plugin.csi.PluginApplication.Configuration
import datadog.trace.plugin.csi.impl.AdviceGeneratorImpl
import datadog.trace.plugin.csi.impl.BaseCsiPluginTest
import datadog.trace.plugin.csi.impl.CallSiteSpecification
import datadog.trace.plugin.csi.impl.assertion.AdviceAssert
import datadog.trace.plugin.csi.impl.assertion.AssertBuilder
import datadog.trace.plugin.csi.impl.assertion.CallSiteAssert
import datadog.trace.plugin.csi.impl.assertion.StatementsAssert
import datadog.trace.plugin.csi.impl.ext.tests.IastExtensionCallSite
import groovy.transform.CompileDynamic
import spock.lang.TempDir

import javax.servlet.http.HttpServletRequest
import java.lang.reflect.Executable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static datadog.trace.plugin.csi.impl.CallSiteFactory.pointcutParser
import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType

@CompileDynamic
class IastExtensionTest extends BaseCsiPluginTest {

  @TempDir
  private File buildDir
  private Path targetFolder
  private Path projectFolder
  private Path srcFolder

  void setup() {
    targetFolder = buildDir.toPath().resolve('target')
    Files.createDirectories(targetFolder)
    projectFolder = buildDir.toPath().resolve('project')
    Files.createDirectories(projectFolder)
    srcFolder = projectFolder.resolve('src/main/java')
    Files.createDirectories(srcFolder)
  }

  void 'test that extension only applies to iast advices'() {
    setup:
    final type = classNameToType(typeName)
    final callSite = Mock(CallSiteSpecification) {
      getSpi() >> type
    }
    final extension = new IastExtension()

    when:
    final applies = extension.appliesTo(callSite)

    then:
    applies == expected

    where:
    typeName                           | expected
    CallSites.name                     | false
    IastExtension.IAST_CALL_SITES_FQCN | true
  }

  void 'test that extension generates a call site with telemetry'() {
    setup:
    final config = Mock(Configuration) {
      getTargetFolder() >> targetFolder
      getSrcFolder() >> getCallSiteSrcFolder()
      getClassPath() >> []
    }
    final spec = buildClassSpecification(IastExtensionCallSite)
    final generator = buildAdviceGenerator(buildDir)
    final result = generator.generate(spec)
    if (!result.success) {
      throw new IllegalArgumentException("Error with call site ${IastExtensionCallSite}")
    }
    final extension = new IastExtension()

    when:
    extension.apply(config, result)

    then: 'the call site provider is modified with telemetry'
    assertNoErrors result
    assertCallSites(result.file) {
      helpers(IastExtensionCallSite.class.name, "${IastExtensionCallSite.class.name}Dynamic")
      advices(0) {
        pointcut('javax/servlet/http/HttpServletRequest', 'getHeader', '(Ljava/lang/String;)Ljava/lang/String;')
        instrumentedMetric('IastMetric.INSTRUMENTED_SOURCE') {
          metricStatements('IastMetricCollector.add(IastMetric.INSTRUMENTED_SOURCE, "http.request.header.name", 1);')
        }
        executedMetric('IastMetric.EXECUTED_SOURCE') {
          metricStatements(
            'handler.field(net.bytebuddy.jar.asm.Opcodes.GETSTATIC, "datadog/trace/api/iast/telemetry/IastMetric", "EXECUTED_SOURCE", "Ldatadog/trace/api/iast/telemetry/IastMetric;");',
            'handler.loadConstant("http.request.header.name");',
            'handler.instruction(net.bytebuddy.jar.asm.Opcodes.ICONST_1);',
            'handler.method(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC, "datadog/trace/api/iast/telemetry/IastMetricCollector", "add", "(Ldatadog/trace/api/iast/telemetry/IastMetric;Ljava/lang/String;I)V", false);'
          )
        }
      }
      advices(1) {
        pointcut('javax/servlet/ServletRequest', 'getReader', '()Ljava/io/BufferedReader;')
        instrumentedMetric('IastMetric.INSTRUMENTED_PROPAGATION') {
          metricStatements('IastMetricCollector.add(IastMetric.INSTRUMENTED_PROPAGATION, 1);')
        }
        executedMetric('IastMetric.EXECUTED_PROPAGATION') {
          metricStatements(
            'handler.field(net.bytebuddy.jar.asm.Opcodes.GETSTATIC, "datadog/trace/api/iast/telemetry/IastMetric", "EXECUTED_PROPAGATION", "Ldatadog/trace/api/iast/telemetry/IastMetric;");',
            'handler.instruction(net.bytebuddy.jar.asm.Opcodes.ICONST_1);',
            'handler.method(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC, "datadog/trace/api/iast/telemetry/IastMetricCollector", "add", "(Ldatadog/trace/api/iast/telemetry/IastMetric;I)V", false);'
          )
        }
      }
      advices(2) {
        pointcut('java/lang/String', '<init>', '([B)V')
      }
    }

    and: 'a new dynamic call site is generated'
    final dynamic = new File(result.file.parentFile, 'IastExtensionCallSiteDynamic.java')
    assertDynamicCallSite(dynamic) {
      interfaces(HasDynamicSupport)
      // BEFORE
      pointcut(String.getDeclaredConstructor(byte[].class)) {
        // invoke the call site
        statement(0) {
          final statement = it.asTryStmt().tryBlock.statements.first.get().toString()
          assert statement == 'IastExtensionCallSite.beforeByteArrayCtor(new Object[] { arguments[1] });'
        }
        // invoke original method and return the value
        statement(1, 'return callSite.getTarget().invokeWithArguments(arguments);')
      }
      // AROUND
      pointcut(Random.getDeclaredMethod('nextBoolean')) {
        // invoke the call site and return the result
        statement(0) {
          final statement = it.asTryStmt().tryBlock.statements.first.get().toString()
          assert statement == 'return IastExtensionCallSite.aroundNextBoolean((Random) arguments[0]);'
        }
        // invoke the original method in case of error
        statement(1, 'return callSite.getTarget().invokeWithArguments(arguments);')
      }
      // AFTER
      pointcut(HttpServletRequest.getDeclaredMethod('getHeader', String)) {
        // invoke original method
        statement(0, 'Object result = callSite.getTarget().invokeWithArguments(arguments);')
        // invoke call site adding the result
        statement(1) {
          final statement = it.asTryStmt().tryBlock.statements.first.get().toString()
          assert statement == 'IastExtensionCallSite.afterGetHeader((HttpServletRequest) arguments[0], (String) arguments[1], (String) result);'
        }
        statement(2, 'return result;')
      }
    }
  }

  private static AdviceGenerator buildAdviceGenerator(final File targetFolder) {
    return new AdviceGeneratorImpl(targetFolder, pointcutParser())
  }

  private static Path getCallSiteSrcFolder() {
    final file = Thread.currentThread().contextClassLoader.getResource('')
    return Paths.get(file.toURI()).resolve('../../../../src/test/java')
  }

  private static void assertCallSites(final File generated, @DelegatesTo(IastExtensionCallSiteAssert) final Closure closure) {
    final asserter = new IastExtensionAssertBuilder(generated).build()
    closure.delegate = asserter
    closure(asserter)
  }

  private static void assertDynamicCallSite(final File generated, @DelegatesTo(IastExtensionDynamicCallSiteAssert) final Closure closure) {
    final asserter = new IastExtensionDynamicCallSiteAssertBuilder(generated).build()
    closure.delegate = asserter
    closure(asserter)
  }

  static class IastExtensionDynamicCallSiteAssertBuilder {
    private final File file

    IastExtensionDynamicCallSiteAssertBuilder(final File file) {
      this.file = file
    }

    IastExtensionDynamicCallSiteAssert build() {
      final javaFile = parseJavaFile(file)
      assert javaFile.parsed == Node.Parsedness.PARSED
      final targetType = javaFile.primaryType.get().asClassOrInterfaceDeclaration()
      final interfaces = getInterfaces(targetType)
      final pointcuts = targetType.getMethods().findAll {
        it.getAnnotationByName('DynamicHelper').isPresent()
      }.collectEntries { getDynamicPointcut(it) }
      return new IastExtensionDynamicCallSiteAssert([
        interfaces: interfaces,
        pointcuts : pointcuts
      ])
    }

    protected def getDynamicPointcut(final MethodDeclaration method) {
      final helper = method.getAnnotationByName('DynamicHelper').get().asNormalAnnotationExpr()
      final annotationParams = helper.getPairs().collectEntries { [(it.nameAsString): it.value] } as Map<String, Expression>
      final owner = resolve(annotationParams['owner'].asClassExpr().getType())
      final methodName = annotationParams['method'].asStringLiteralExpr().getValue()
      final returnType = resolve(annotationParams['returnType'].asClassExpr().getType())
      final argumentTypes = annotationParams['argumentTypes'].asArrayInitializerExpr().values*.asClassExpr().collect { resolve(it.getType()) }
      final Executable resolved
      if (methodName == '<init>') {
        resolved = owner.getDeclaredConstructor(argumentTypes as Class<?>[])
      } else {
        resolved = owner.getDeclaredMethod(methodName, argumentTypes as Class<?>[])
        assert returnType == resolved.returnType
      }
      return [(resolved): new StatementsAssert(statements: method.getBody().get().statements)]
    }

    protected List<Class<?>> getInterfaces(final ClassOrInterfaceDeclaration type) {
      return type.asClassOrInterfaceDeclaration().implementedTypes.collect {
        resolve(it.asClassOrInterfaceType())
      }
    }

    protected Class<?> resolve(final Type type) {
      final resolved = type.resolve()
      if (resolved instanceof ResolvedArrayType) {
        return Class.forName(resolved.toDescriptor().replaceAll('/', '.'))
      } else if (resolved instanceof ResolvedVoidType) {
        return void.class
      } else if (resolved instanceof ResolvedPrimitiveType) {
        return resolved.boxTypeClass.TYPE
      }
      return resolved.typeDeclaration.get().clazz
    }

    private static CompilationUnit parseJavaFile(final File file)
      throws FileNotFoundException {
      final JavaSymbolSolver solver = new JavaSymbolSolver(typeResolver());
      final JavaParser parser = new JavaParser(new ParserConfiguration().setSymbolResolver(solver));
      return parser.parse(file).getResult().get();
    }
  }

  static class IastExtensionDynamicCallSiteAssert {
    protected Collection<Class<?>> interfaces
    protected Map<Executable, StatementsAssert> pointcuts


    void interfaces(Class<?>... values) {
      final list = values.toList()
      assert list == interfaces
    }

    void pointcut(Executable method, @DelegatesTo(StatementsAssert) Closure closure) {
      final asserter = pointcuts[method]
      closure.delegate = asserter
      closure(asserter)
    }
  }

  static class IastExtensionCallSiteAssert extends CallSiteAssert {

    IastExtensionCallSiteAssert(CallSiteAssert base) {
      interfaces = base.interfaces
      helpers = base.helpers
      advices = base.advices
      enabled = base.enabled
      enabledArgs = base.enabledArgs
    }

    void advices(int index, @DelegatesTo(IastExtensionAdviceAssert) Closure closure) {
      final asserter = advices[index]
      closure.delegate = asserter
      closure(asserter)
    }

    void advices(@DelegatesTo(IastExtensionAdviceAssert) Closure closure) {
      advices.each {
        closure.delegate = it
        closure(it)
      }
    }
  }

  static class IastExtensionAdviceAssert extends AdviceAssert {

    protected IastExtensionMetricAsserter instrumented
    protected IastExtensionMetricAsserter executed

    void instrumentedMetric(final String metric, @DelegatesTo(IastExtensionMetricAsserter) Closure closure) {
      assert metric == instrumented.metric
      closure.delegate = instrumented
      closure(instrumented)
    }

    void executedMetric(final String metric, @DelegatesTo(IastExtensionMetricAsserter) Closure closure) {
      assert metric == executed.metric
      closure.delegate = executed
      closure(executed)
    }
  }

  static class IastExtensionMetricAsserter {
    protected String metric
    protected StatementsAssert statements

    void metricStatements(String... values) {
      statements.asString(values)
    }

    void metricStatements(@DelegatesTo(StatementsAssert) Closure closure) {
      closure.delegate = statements
      closure(statements)
    }
  }

  static class IastExtensionAssertBuilder extends AssertBuilder<IastExtensionCallSiteAssert> {

    IastExtensionAssertBuilder(File file) {
      super(file)
    }

    @Override
    IastExtensionCallSiteAssert build() {
      final base = super.build()
      return new IastExtensionCallSiteAssert(base)
    }

    @Override
    protected List<AdviceAssert> getAdvices(ClassOrInterfaceDeclaration type) {
      final acceptMethod = type.getMethodsByName('accept').first()
      return getMethodCalls(acceptMethod).findAll {
        it.nameAsString == 'addAdvice'
      }.collect {
        def (owner, method, descriptor) = it.arguments.subList(0, 3)*.asStringLiteralExpr()*.asString()
        final handlerLambda = it.arguments[3].asLambdaExpr()
        final statements = handlerLambda.body.asBlockStmt().statements
        final instrumentedStmt = statements.get(0).asIfStmt()
        final executedStmt = statements.get(1).asIfStmt()
        return new IastExtensionAdviceAssert([
          owner       : owner,
          method      : method,
          descriptor  : descriptor,
          instrumented: buildMetricAsserter(instrumentedStmt),
          executed    : buildMetricAsserter(executedStmt),
          statements  : new StatementsAssert(statements: statements.findAll { !it.isIfStmt() })
        ])
      }
    }

    protected IastExtensionMetricAsserter buildMetricAsserter(final IfStmt ifStmt) {
      final condition = ifStmt.getCondition().asMethodCallExpr()
      return new IastExtensionMetricAsserter(
        metric: condition.getScope().get().toString(),
        statements: new StatementsAssert(statements: ifStmt.getThenStmt().asBlockStmt().statements)
      )
    }
  }
}
