package datadog.trace.plugin.csi.impl.ext;

import static com.github.javaparser.ast.Modifier.Keyword.PRIVATE;
import static com.github.javaparser.ast.Modifier.Keyword.PUBLIC;
import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver;
import static datadog.trace.plugin.csi.util.JavaParserUtils.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.visitor.GenericVisitorAdapter;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import datadog.trace.plugin.csi.AdviceGenerator.AdviceResult;
import datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult;
import datadog.trace.plugin.csi.Extension;
import datadog.trace.plugin.csi.PluginApplication.Configuration;
import datadog.trace.plugin.csi.TypeResolver;
import datadog.trace.plugin.csi.impl.CallSiteSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification;
import datadog.trace.plugin.csi.util.CallSiteUtils;
import datadog.trace.plugin.csi.util.MethodType;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

@SuppressWarnings("OptionalGetWithoutIsPresent")
public class IastExtension implements Extension {

  static final String WITH_TELEMETRY_SUFFIX = "WithTelemetry";
  private static final String IAST_ADVICE_CLASS = "IastAdvice";
  static final String IAST_ADVICE_FQCN = "datadog.trace.api.iast." + IAST_ADVICE_CLASS;
  private static final String HAS_TELEMETRY_INTERFACE = IAST_ADVICE_CLASS + ".HasTelemetry";
  private static final String KIND_CLASS = IAST_ADVICE_CLASS + ".Kind";
  private static final String IAST_METRIC_COLLECTOR_CLASS = "IastMetricCollector";
  private static final String IAST_METRIC_COLLECTOR_FQCN =
      "datadog.trace.api.iast.telemetry." + IAST_METRIC_COLLECTOR_CLASS;
  private static final String IAST_METRIC_CLASS = "IastMetric";
  private static final String IAST_METRIC_FQCN =
      "datadog.trace.api.iast.telemetry." + IAST_METRIC_CLASS;

  @Override
  public boolean appliesTo(@Nonnull final CallSiteSpecification spec) {
    return IAST_ADVICE_FQCN.equals(spec.getSpi().getClassName());
  }

  @Override
  public void apply(
      @Nonnull final Configuration configuration, @Nonnull final CallSiteResult result)
      throws Exception {
    addTelemetry(configuration, result);
  }

  private void addTelemetry(final Configuration configuration, final CallSiteResult result)
      throws Exception {
    final Type callSiteClass = result.getSpecification().getClazz();
    final String fileSeparatorPattern = File.separator.equals("\\") ? "\\\\" : File.separator;
    final String callSiteFile =
        callSiteClass.getClassName().replaceAll("\\.", fileSeparatorPattern);
    final TypeResolver resolver = getTypeResolver(configuration);

    // new call site with telemetry embedded
    final CompilationUnit callSite = parseSourceFile(configuration, resolver, callSiteFile);
    final Path newFile =
        configuration.getTargetFolder().resolve(callSiteFile + WITH_TELEMETRY_SUFFIX + ".java");
    callSite.setStorage(newFile);
    final TypeDeclaration<?> callSiteType = getPrimaryType(callSite);
    callSite.addImport(IAST_METRIC_COLLECTOR_FQCN);
    callSite.addImport(IAST_METRIC_FQCN);
    final String name = callSiteType.getNameAsString() + WITH_TELEMETRY_SUFFIX;
    final ClassOrInterfaceDeclaration withTelemetry = callSiteType.asClassOrInterfaceDeclaration();
    withTelemetry.setName(name);
    final AdviceMetadata globalMetadata = AdviceMetadata.findAdviceMetadata(callSiteType);
    for (final MethodDeclaration method : callSiteType.getMethods()) {
      if (isCallSite(method)) {
        final AdviceMetadata methodMetadata = AdviceMetadata.findAdviceMetadata(method);
        final AdviceMetadata metaData = methodMetadata != null ? methodMetadata : globalMetadata;
        // if the call site class or the method has been annotated then apply the extension
        if (metaData != null) {
          handleCallSiteMethod(resolver, findAdvices(result, method), metaData, callSite, method);
        }
      }
    }
  }

  private void handleCallSiteMethod(
      final TypeResolver resolver,
      final List<AdviceResult> advices,
      final AdviceMetadata adviceMetadata,
      final CompilationUnit javaClass,
      final MethodDeclaration method)
      throws FileNotFoundException {
    addCollectorStatementToCallSite(javaClass, method, adviceMetadata);
    for (final AdviceResult advice : advices) {
      addTelemetryToAdvice(resolver, advice, adviceMetadata);
    }
  }

  private void addTelemetryToAdvice(
      final TypeResolver resolver, final AdviceResult advice, final AdviceMetadata metaData)
      throws FileNotFoundException {
    final CompilationUnit adviceSource = parseJavaFile(resolver, advice.getFile());
    adviceSource.addImport(IAST_METRIC_COLLECTOR_FQCN);
    adviceSource.addImport(IAST_METRIC_FQCN);
    adviceSource.addImport(IAST_ADVICE_FQCN);
    final ClassOrInterfaceDeclaration mainType =
        getPrimaryType(adviceSource).asClassOrInterfaceDeclaration();
    mainType.addImplementedType(HAS_TELEMETRY_INTERFACE);
    if (mainType.getFieldByName("telemetry").isPresent()) {
      return; // source file already processed
    }

    final Type callSite = advice.getSpecification().getAdvice().getOwner();
    final StringLiteralExpr callSiteLiteral = findCallSiteLiteral(mainType, callSite);
    final ArrayCreationExpr helperClassNames = findHelperClassNames(mainType);
    addTelemetryFields(mainType, callSiteLiteral, helperClassNames);
    addCollectStatementToApply(adviceSource, mainType, metaData);
    addEnableTelemetryField(mainType, callSite, helperClassNames);
    addKindMethod(metaData, mainType);

    // save the advice
    adviceSource.getStorage().get().save();
  }

  private void addTelemetryFields(
      final ClassOrInterfaceDeclaration mainType,
      final StringLiteralExpr callSiteLiteral,
      final ArrayCreationExpr helperClassNames) {
    // flag for telemetry
    mainType
        .addFieldWithInitializer("boolean", "telemetry", new BooleanLiteralExpr(false))
        .setModifiers(PRIVATE);

    // replace the call site literal expression to a field access
    replaceInParent(callSiteLiteral, accessLocalField("callSite"));
    mainType
        .addFieldWithInitializer(
            "String", "callSite", new StringLiteralExpr(callSiteLiteral.getValue()))
        .setModifiers(PRIVATE);

    // replace the helperClassNames initialization with a field definition
    replaceInParent(helperClassNames, accessLocalField("helperClassNames"));
    mainType
        .addFieldWithInitializer("String[]", "helperClassNames", helperClassNames)
        .setModifiers(PRIVATE);
  }

  private void addCollectStatementToApply(
      final CompilationUnit javaClass,
      final ClassOrInterfaceDeclaration mainType,
      final AdviceMetadata metaData) {
    final MethodDeclaration applyMethod = mainType.getMethodsByName("apply").get(0);
    final BlockStmt ifTelemetry = new BlockStmt();
    ifTelemetry.addStatement(iastTelemetryCollectorAddMethod(metaData, "INSTRUMENTED"));
    applyMethod
        .getBody()
        .get()
        .addStatement(
            0, new IfStmt().setCondition(accessLocalField("telemetry")).setThenStmt(ifTelemetry));
  }

  private static void addEnableTelemetryField(
      final ClassOrInterfaceDeclaration mainType,
      final Type callSite,
      final ArrayCreationExpr helperClassNames) {
    final MethodDeclaration enableTelemetryMethod =
        mainType
            .addMethod("enableTelemetry")
            .addParameter(boolean.class, "enableRuntime")
            .setModifiers(PUBLIC)
            .addAnnotation(Override.class);
    final BlockStmt enableTelemetryBody = new BlockStmt();
    enableTelemetryBody.addStatement(
        new AssignExpr()
            .setTarget(accessLocalField("telemetry"))
            .setValue(new BooleanLiteralExpr(true)));
    final IfStmt ifStmt = new IfStmt().setCondition(new NameExpr("enableRuntime"));
    final BlockStmt ifRuntime = new BlockStmt();
    ifRuntime.addStatement(
        updateLocalField(
            "callSite", new StringLiteralExpr(callSite.getInternalName() + WITH_TELEMETRY_SUFFIX)));
    final NodeList<Expression> helperClassNamesWithTelemetry = new NodeList<>();
    for (final Expression helper : helperClassNames.getInitializer().get().getValues()) {
      final StringLiteralExpr strExp = helper.asStringLiteralExpr();
      if (strExp.getValue().equals(callSite.getClassName())) {
        // replace the old call site
        helperClassNamesWithTelemetry.add(
            new StringLiteralExpr(callSite.getClassName() + WITH_TELEMETRY_SUFFIX));
      } else {
        helperClassNamesWithTelemetry.add(strExp);
      }
    }
    ifRuntime.addStatement(
        updateLocalField(
            "helperClassNames",
            new ArrayCreationExpr()
                .setElementType("String")
                .setInitializer(
                    new ArrayInitializerExpr().setValues(helperClassNamesWithTelemetry))));
    ifStmt.setThenStmt(ifRuntime);
    enableTelemetryBody.addStatement(ifStmt);
    enableTelemetryMethod.setBody(enableTelemetryBody);
  }

  private static void addKindMethod(
      final AdviceMetadata metaData, final ClassOrInterfaceDeclaration mainType) {
    // add kind method
    mainType.addMember(
        singleStatementMethod(
            "kind",
            KIND_CLASS,
            new FieldAccessExpr().setScope(new NameExpr(KIND_CLASS)).setName(metaData.getKind()),
            true));
  }

  private StringLiteralExpr findCallSiteLiteral(
      final ClassOrInterfaceDeclaration mainType, final Type callSite) {
    final String callSiteInternalName = callSite.getInternalName();
    final MethodDeclaration applyMethod = mainType.getMethodsByName("apply").get(0);
    return applyMethod.accept(
        new GenericVisitorAdapter<StringLiteralExpr, Void>() {
          @Override
          public StringLiteralExpr visit(final StringLiteralExpr string, final Void arg) {
            if (callSiteInternalName.equals(string.getValue())) {
              return string;
            }
            return null;
          }
        },
        null);
  }

  private ArrayCreationExpr findHelperClassNames(final ClassOrInterfaceDeclaration mainType) {
    final MethodDeclaration helperClassNamesMethod =
        mainType.getMethodsByName("helperClassNames").get(0);
    return helperClassNamesMethod.accept(
        new GenericVisitorAdapter<ArrayCreationExpr, Void>() {
          @Override
          public ArrayCreationExpr visit(final ReturnStmt n, final Void arg) {
            return n.getExpression().get().asArrayCreationExpr();
          }
        },
        null);
  }

  private void addCollectorStatementToCallSite(
      final CompilationUnit javaClass,
      final MethodDeclaration method,
      final AdviceMetadata metaData) {
    final BlockStmt body = method.getBody().get();
    if (!body.getStatements().isEmpty()
        && body.getStatements().get(0).toString().contains(IAST_METRIC_COLLECTOR_CLASS)) {
      return; // call site already processed
    }
    body.addStatement(0, iastTelemetryCollectorAddMethod(metaData, "EXECUTED"));
    javaClass.getStorage().get().save();
  }

  private MethodCallExpr iastTelemetryCollectorAddMethod(
      final AdviceMetadata metaData, final String type) {
    final StringBuilder metric = new StringBuilder(type).append("_").append(metaData.getKind());
    if (metaData.getTag() != null) {
      // Uses the name of the field to compose the name of the metric
      final Expression tag = metaData.getTag();
      metric.append("_").append(tag.asFieldAccessExpr().getName());
    }
    return new MethodCallExpr()
        .setScope(new NameExpr(IAST_METRIC_COLLECTOR_CLASS))
        .setName("add")
        .addArgument(
            new FieldAccessExpr()
                .setScope(new NameExpr(IAST_METRIC_CLASS))
                .setName(metric.toString()))
        .addArgument(intLiteral(1));
  }

  private List<AdviceResult> findAdvices(
      final CallSiteResult result, final MethodDeclaration method) {
    return result.getAdvices().stream()
        .filter(
            it -> {
              final AdviceSpecification adviceSpec = it.getSpecification();
              final MethodType advice = adviceSpec.getAdvice();
              if (!advice.getMethodName().equals(method.getNameAsString())) {
                return false;
              }
              final Type adviceMethod = advice.getMethodType();
              if (adviceMethod.getArgumentTypes().length != method.getParameters().size()) {
                return false;
              }
              int index = 0;
              for (final Type type : adviceMethod.getArgumentTypes()) {
                final Parameter parameter = method.getParameter(index);
                final ResolvedType resolved = parameter.resolve().getType();
                if (!resolved.describe().equals(type.getClassName().replaceAll("\\$", "."))) {
                  return false;
                }
                index++;
              }
              return true;
            })
        .collect(Collectors.toList());
  }

  private static boolean isCallSite(final MethodDeclaration method) {
    return Stream.of("Before", "Around", "After")
        .map(method::getAnnotationByName)
        .anyMatch(Optional::isPresent);
  }

  private static TypeResolver getTypeResolver(final Configuration configuration) {
    final URL[] urls =
        configuration.getClassPath().stream().map(CallSiteUtils::toURL).toArray(URL[]::new);
    final ClassLoader loader = new URLClassLoader(urls);
    return typeResolver(loader, Thread.currentThread().getContextClassLoader());
  }

  private static CompilationUnit parseSourceFile(
      final Configuration configuration, final TypeResolver resolver, final String file)
      throws FileNotFoundException {
    final Path callSiteSource = configuration.getSrcFolder().resolve(file + ".java");
    if (!Files.exists(callSiteSource)) {
      throw new RuntimeException("Error finding original call site at " + callSiteSource);
    }
    return parseJavaFile(resolver, callSiteSource.toFile());
  }

  private static CompilationUnit parseJavaFile(final TypeResolver resolver, final File file)
      throws FileNotFoundException {
    final JavaSymbolSolver solver = new JavaSymbolSolver(resolver);
    final JavaParser parser = new JavaParser(new ParserConfiguration().setSymbolResolver(solver));
    return parser.parse(file).getResult().get();
  }

  private static class AdviceMetadata {
    private final String kind;
    private final Expression tag;

    private AdviceMetadata(final String kind, final Expression tag) {
      this.kind = kind;
      this.tag = tag;
    }

    private static AdviceMetadata findAdviceMetadata(final BodyDeclaration<?> target) {
      return target.getAnnotations().stream()
          .filter(AdviceMetadata::isAdviceAnnotation)
          .map(
              annotation -> {
                final Expression tag = getAdviceAnnotationExpression(annotation);
                final String typeName = annotation.getName().getId();
                return new AdviceMetadata(typeName.toUpperCase(), tag);
              })
          .findFirst()
          .orElse(null);
    }

    private static boolean isAdviceAnnotation(final AnnotationExpr expr) {
      final String identifier = expr.getName().getIdentifier();
      return identifier.equals("Source")
          || identifier.equals("Propagation")
          || identifier.equals("Sink");
    }

    private static Expression getAdviceAnnotationExpression(final AnnotationExpr expr) {
      if (expr.isMarkerAnnotationExpr()) {
        return null;
      } else if (expr.isSingleMemberAnnotationExpr()) {
        return expr.asSingleMemberAnnotationExpr().getMemberValue();
      } else {
        final List<MemberValuePair> pairs = expr.asNormalAnnotationExpr().getPairs();
        return pairs.stream()
            .filter(it -> it.getName().toString().equals("value"))
            .map(MemberValuePair::getValue)
            .findFirst()
            .orElse(null);
      }
    }

    public String getKind() {
      return kind;
    }

    public Expression getTag() {
      return tag;
    }
  }
}
