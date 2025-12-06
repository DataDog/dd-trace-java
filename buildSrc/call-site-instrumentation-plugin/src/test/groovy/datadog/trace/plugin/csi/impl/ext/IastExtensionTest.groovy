package datadog.trace.plugin.csi.impl.ext

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.stmt.IfStmt
import datadog.trace.agent.tooling.csi.CallSites
import datadog.trace.plugin.csi.AdviceGenerator
import datadog.trace.plugin.csi.PluginApplication.Configuration
import datadog.trace.plugin.csi.impl.AdviceGeneratorImpl
import datadog.trace.plugin.csi.impl.BaseCsiPluginTest
import datadog.trace.plugin.csi.impl.CallSiteSpecification
import datadog.trace.plugin.csi.impl.assertion.AdviceAssert
import datadog.trace.plugin.csi.impl.assertion.AssertBuilder
import datadog.trace.plugin.csi.impl.assertion.CallSiteAssert
import datadog.trace.plugin.csi.impl.ext.tests.IastExtensionCallSite
import datadog.trace.plugin.csi.impl.ext.tests.SourceTypes
import groovy.transform.CompileDynamic
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static datadog.trace.plugin.csi.impl.CallSiteFactory.pointcutParser
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
      advices(0) {
        pointcut('javax/servlet/http/HttpServletRequest', 'getHeader', '(Ljava/lang/String;)Ljava/lang/String;')
        instrumentedMetric('IastMetric.INSTRUMENTED_SOURCE') {
          metricStatements('IastMetricCollector.add(IastMetric.INSTRUMENTED_SOURCE, (byte) 3, 1);')
        }
        executedMetric('IastMetric.EXECUTED_SOURCE') {
          metricStatements(
            'handler.field(net.bytebuddy.jar.asm.Opcodes.GETSTATIC, "datadog/trace/api/iast/telemetry/IastMetric", "EXECUTED_SOURCE", "Ldatadog/trace/api/iast/telemetry/IastMetric;");',
            'handler.instruction(net.bytebuddy.jar.asm.Opcodes.ICONST_3);',
            'handler.instruction(net.bytebuddy.jar.asm.Opcodes.ICONST_1);',
            'handler.method(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC, "datadog/trace/api/iast/telemetry/IastMetricCollector", "add", "(Ldatadog/trace/api/iast/telemetry/IastMetric;BI)V", false);'
            )
        }
      }
      advices(1) {
        pointcut('javax/servlet/http/HttpServletRequest', 'getInputStream', '()Ljavax/servlet/ServletInputStream;')
        instrumentedMetric('IastMetric.INSTRUMENTED_SOURCE') {
          metricStatements('IastMetricCollector.add(IastMetric.INSTRUMENTED_SOURCE, (byte) 127, 1);')
        }
        executedMetric('IastMetric.EXECUTED_SOURCE') {
          metricStatements(
            'handler.field(net.bytebuddy.jar.asm.Opcodes.GETSTATIC, "datadog/trace/api/iast/telemetry/IastMetric", "EXECUTED_SOURCE", "Ldatadog/trace/api/iast/telemetry/IastMetric;");',
            'handler.instruction(net.bytebuddy.jar.asm.Opcodes.BIPUSH, 127);',
            'handler.instruction(net.bytebuddy.jar.asm.Opcodes.ICONST_1);',
            'handler.method(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC, "datadog/trace/api/iast/telemetry/IastMetricCollector", "add", "(Ldatadog/trace/api/iast/telemetry/IastMetric;BI)V", false);'
            )
        }
      }
      advices(2) {
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
    }
  }

  private static AdviceGenerator buildAdviceGenerator(final File targetFolder) {
    return new AdviceGeneratorImpl(targetFolder, pointcutParser())
  }

  private static Path getCallSiteSrcFolder() {
    final file = Thread.currentThread().contextClassLoader.getResource('')
    return Paths.get(file.toURI()).resolve('../../../../src/test/java')
  }

  private static ClassOrInterfaceDeclaration parse(final File path) {
    final parsedAdvice = new JavaParser().parse(path).getResult().get()
    return parsedAdvice.primaryType.get().asClassOrInterfaceDeclaration()
  }

  private static void assertCallSites(final File generated, @DelegatesTo(IastExtensionCallSiteAssert) final Closure closure) {
    final asserter = new IastExtensionAssertBuilder(generated).build()
    closure.delegate = asserter
    closure(asserter)
  }

  static class IastExtensionCallSiteAssert extends CallSiteAssert {

    IastExtensionCallSiteAssert(CallSiteAssert base) {
      interfaces = base.interfaces
      spi = base.spi
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
    protected Collection<String> statements

    void metricStatements(String... values) {
      assert values.toList() == statements
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
        def (owner, method, descriptor) = it.arguments.subList(1, 4)*.asStringLiteralExpr()*.asString()
        final handlerLambda = it.arguments[4].asLambdaExpr()
        final statements = handlerLambda.body.asBlockStmt().statements
        final instrumentedStmt = statements.get(0).asIfStmt()
        final executedStmt = statements.get(1).asIfStmt()
        return new IastExtensionAdviceAssert([
          owner     : owner,
          method    : method,
          descriptor: descriptor,
          instrumented : buildMetricAsserter(instrumentedStmt),
          executed: buildMetricAsserter(executedStmt),
          statements: statements.findAll {
            !it.isIfStmt()
          }
        ])
      }
    }

    protected IastExtensionMetricAsserter buildMetricAsserter(final IfStmt ifStmt) {
      final condition = ifStmt.getCondition().asMethodCallExpr()
      return new IastExtensionMetricAsserter(
        metric: condition.getScope().get().toString(),
        statements: ifStmt.getThenStmt().asBlockStmt().statements*.toString()
        )
    }
  }
}
