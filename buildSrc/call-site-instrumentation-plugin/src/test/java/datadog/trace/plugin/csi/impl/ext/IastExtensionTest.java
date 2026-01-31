package datadog.trace.plugin.csi.impl.ext;

import static datadog.trace.plugin.csi.impl.CallSiteFactory.pointcutParser;
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.stmt.IfStmt;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.plugin.csi.AdviceGenerator;
import datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult;
import datadog.trace.plugin.csi.PluginApplication.Configuration;
import datadog.trace.plugin.csi.impl.AdviceGeneratorImpl;
import datadog.trace.plugin.csi.impl.BaseCsiPluginTest;
import datadog.trace.plugin.csi.impl.CallSiteSpecification;
import datadog.trace.plugin.csi.impl.assertion.AdviceAssert;
import datadog.trace.plugin.csi.impl.assertion.AssertBuilder;
import datadog.trace.plugin.csi.impl.assertion.CallSiteAssert;
import datadog.trace.plugin.csi.impl.ext.tests.IastExtensionCallSite;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.Type;

class IastExtensionTest extends BaseCsiPluginTest {

  @TempDir private File buildDir;
  private Path targetFolder;
  private Path projectFolder;
  private Path srcFolder;

  @BeforeEach
  void setup() throws Exception {
    targetFolder = buildDir.toPath().resolve("target");
    Files.createDirectories(targetFolder);
    projectFolder = buildDir.toPath().resolve("project");
    Files.createDirectories(projectFolder);
    srcFolder = projectFolder.resolve("src/main/java");
    Files.createDirectories(srcFolder);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      nullValues = "null",
      value = {
        "datadog.trace.agent.tooling.csi.CallSites      | false",
        "datadog.trace.agent.tooling.iast.IastCallSites | true"
      })
  void testThatExtensionOnlyAppliesToIastAdvices(String typeName, boolean expected) {
    Type type = classNameToType(typeName);
    Type[] types = new Type[] {type};
    CallSiteSpecification callSite = mock(CallSiteSpecification.class);
    when(callSite.getSpi()).thenReturn(types);
    IastExtension extension = new IastExtension();

    boolean applies = extension.appliesTo(callSite);

    assertEquals(expected, applies);
  }

  @Test
  void testThatExtensionGeneratesACallSiteWithTelemetry() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getTargetFolder()).thenReturn(targetFolder);
    when(config.getSrcFolder()).thenReturn(getCallSiteSrcFolder());
    when(config.getClassPath()).thenReturn(Collections.emptyList());
    CallSiteSpecification spec = buildClassSpecification(IastExtensionCallSite.class);
    AdviceGenerator generator = buildAdviceGenerator(buildDir);
    CallSiteResult result = generator.generate(spec);
    assertTrue(result.isSuccess());
    IastExtension extension = new IastExtension();

    extension.apply(config, result);

    assertNoErrors(result);
    IastExtensionCallSiteAssert asserter = assertCallSites(result.getFile());
    asserter.iastAdvices(
        0,
        advice -> {
          advice.pointcut(
              "javax/servlet/http/HttpServletRequest",
              "getHeader",
              "(Ljava/lang/String;)Ljava/lang/String;");
          advice.instrumentedMetric(
              "IastMetric.INSTRUMENTED_SOURCE",
              metric -> {
                metric.metricStatements(
                    "IastMetricCollector.add(IastMetric.INSTRUMENTED_SOURCE, (byte) 3, 1);");
              });
          advice.executedMetric(
              "IastMetric.EXECUTED_SOURCE",
              metric -> {
                metric.metricStatements(
                    "handler.field(net.bytebuddy.jar.asm.Opcodes.GETSTATIC, \"datadog/trace/api/iast/telemetry/IastMetric\", \"EXECUTED_SOURCE\", \"Ldatadog/trace/api/iast/telemetry/IastMetric;\");",
                    "handler.instruction(net.bytebuddy.jar.asm.Opcodes.ICONST_3);",
                    "handler.instruction(net.bytebuddy.jar.asm.Opcodes.ICONST_1);",
                    "handler.method(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC, \"datadog/trace/api/iast/telemetry/IastMetricCollector\", \"add\", \"(Ldatadog/trace/api/iast/telemetry/IastMetric;BI)V\", false);");
              });
        });
    asserter.iastAdvices(
        1,
        advice -> {
          advice.pointcut(
              "javax/servlet/http/HttpServletRequest",
              "getInputStream",
              "()Ljavax/servlet/ServletInputStream;");
          advice.instrumentedMetric(
              "IastMetric.INSTRUMENTED_SOURCE",
              metric -> {
                metric.metricStatements(
                    "IastMetricCollector.add(IastMetric.INSTRUMENTED_SOURCE, (byte) 127, 1);");
              });
          advice.executedMetric(
              "IastMetric.EXECUTED_SOURCE",
              metric -> {
                metric.metricStatements(
                    "handler.field(net.bytebuddy.jar.asm.Opcodes.GETSTATIC, \"datadog/trace/api/iast/telemetry/IastMetric\", \"EXECUTED_SOURCE\", \"Ldatadog/trace/api/iast/telemetry/IastMetric;\");",
                    "handler.instruction(net.bytebuddy.jar.asm.Opcodes.BIPUSH, 127);",
                    "handler.instruction(net.bytebuddy.jar.asm.Opcodes.ICONST_1);",
                    "handler.method(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC, \"datadog/trace/api/iast/telemetry/IastMetricCollector\", \"add\", \"(Ldatadog/trace/api/iast/telemetry/IastMetric;BI)V\", false);");
              });
        });
    asserter.iastAdvices(
        2,
        advice -> {
          advice.pointcut(
              "javax/servlet/ServletRequest", "getReader", "()Ljava/io/BufferedReader;");
          advice.instrumentedMetric(
              "IastMetric.INSTRUMENTED_PROPAGATION",
              metric -> {
                metric.metricStatements(
                    "IastMetricCollector.add(IastMetric.INSTRUMENTED_PROPAGATION, 1);");
              });
          advice.executedMetric(
              "IastMetric.EXECUTED_PROPAGATION",
              metric -> {
                metric.metricStatements(
                    "handler.field(net.bytebuddy.jar.asm.Opcodes.GETSTATIC, \"datadog/trace/api/iast/telemetry/IastMetric\", \"EXECUTED_PROPAGATION\", \"Ldatadog/trace/api/iast/telemetry/IastMetric;\");",
                    "handler.instruction(net.bytebuddy.jar.asm.Opcodes.ICONST_1);",
                    "handler.method(net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC, \"datadog/trace/api/iast/telemetry/IastMetricCollector\", \"add\", \"(Ldatadog/trace/api/iast/telemetry/IastMetric;I)V\", false);");
              });
        });
  }

  private static AdviceGenerator buildAdviceGenerator(File targetFolder) {
    return new AdviceGeneratorImpl(targetFolder, pointcutParser());
  }

  private static Path getCallSiteSrcFolder() throws Exception {
    File file = new File(Thread.currentThread().getContextClassLoader().getResource("").toURI());
    return file.toPath().resolve("../../../../src/test/java");
  }

  private static ClassOrInterfaceDeclaration parse(File path) throws Exception {
    return new JavaParser()
        .parse(path)
        .getResult()
        .get()
        .getPrimaryType()
        .get()
        .asClassOrInterfaceDeclaration();
  }

  private static IastExtensionCallSiteAssert assertCallSites(File generated) {
    try {
      return new IastExtensionAssertBuilder(generated).build();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  static class IastExtensionCallSiteAssert extends CallSiteAssert {

    IastExtensionCallSiteAssert(
        Set<Class<?>> interfaces,
        Set<Class<?>> spi,
        Set<Class<?>> helpers,
        List<AdviceAssert> advices,
        Method enabled,
        Set<String> enabledArgs) {
      super(interfaces, spi, helpers, advices, enabled, enabledArgs);
    }

    public void iastAdvices(int index, Consumer<IastExtensionAdviceAssert> assertions) {
      IastExtensionAdviceAssert asserter = (IastExtensionAdviceAssert) advices.get(index);
      assertions.accept(asserter);
    }
  }

  static class IastExtensionAdviceAssert extends AdviceAssert {

    protected IastExtensionMetricAsserter instrumented;
    protected IastExtensionMetricAsserter executed;

    IastExtensionAdviceAssert(
        String owner,
        String method,
        String descriptor,
        IastExtensionMetricAsserter instrumented,
        IastExtensionMetricAsserter executed,
        List<String> statements) {
      super(null, owner, method, descriptor, statements);
      this.instrumented = instrumented;
      this.executed = executed;
    }

    public void instrumentedMetric(
        String metric, Consumer<IastExtensionMetricAsserter> assertions) {
      assertEquals(metric, instrumented.metric);
      assertions.accept(instrumented);
    }

    public void executedMetric(String metric, Consumer<IastExtensionMetricAsserter> assertions) {
      assertEquals(metric, executed.metric);
      assertions.accept(executed);
    }
  }

  static class IastExtensionMetricAsserter {
    protected String metric;
    protected List<String> statements;

    IastExtensionMetricAsserter(String metric, List<String> statements) {
      this.metric = metric;
      this.statements = statements;
    }

    public void metricStatements(String... values) {
      assertArrayEquals(values, statements.toArray(new String[0]));
    }
  }

  static class IastExtensionAssertBuilder extends AssertBuilder {

    IastExtensionAssertBuilder(File file) {
      super(file);
    }

    @Override
    public IastExtensionCallSiteAssert build() {
      CallSiteAssert base = super.build();
      return new IastExtensionCallSiteAssert(
          base.getInterfaces(),
          base.getSpi(),
          base.getHelpers(),
          base.getAdvices(),
          base.getEnabled(),
          base.getEnabledArgs());
    }

    @Override
    protected List<AdviceAssert> getAdvices(ClassOrInterfaceDeclaration type) {
      return getMethodCalls(type.getMethodsByName("accept").get(0)).stream()
          .filter(methodCall -> methodCall.getNameAsString().equals("addAdvice"))
          .map(
              methodCall -> {
                String owner = methodCall.getArgument(1).asStringLiteralExpr().asString();
                String method = methodCall.getArgument(2).asStringLiteralExpr().asString();
                String descriptor = methodCall.getArgument(3).asStringLiteralExpr().asString();
                List<com.github.javaparser.ast.stmt.Statement> statements =
                    methodCall
                        .getArgument(4)
                        .asLambdaExpr()
                        .getBody()
                        .asBlockStmt()
                        .getStatements();
                IfStmt instrumentedStmt = statements.get(0).asIfStmt();
                IfStmt executedStmt = statements.get(1).asIfStmt();
                List<String> nonIfStatements =
                    statements.stream()
                        .filter(stmt -> !stmt.isIfStmt())
                        .map(Object::toString)
                        .collect(Collectors.toList());
                return new IastExtensionAdviceAssert(
                    owner,
                    method,
                    descriptor,
                    buildMetricAsserter(instrumentedStmt),
                    buildMetricAsserter(executedStmt),
                    nonIfStatements);
              })
          .collect(Collectors.toList());
    }

    protected IastExtensionMetricAsserter buildMetricAsserter(IfStmt ifStmt) {
      String metric = ifStmt.getCondition().asMethodCallExpr().getScope().get().toString();
      List<String> statements =
          ifStmt.getThenStmt().asBlockStmt().getStatements().stream()
              .map(Object::toString)
              .collect(Collectors.toList());
      return new IastExtensionMetricAsserter(metric, statements);
    }
  }
}
