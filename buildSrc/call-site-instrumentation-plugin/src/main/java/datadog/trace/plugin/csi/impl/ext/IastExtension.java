package datadog.trace.plugin.csi.impl.ext;

import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver;
import static datadog.trace.plugin.csi.util.CallSiteConstants.AUTO_SERVICE_FQDN;
import static datadog.trace.plugin.csi.util.CallSiteConstants.OPCODES_FQDN;
import static datadog.trace.plugin.csi.util.CallSiteUtils.deleteFile;
import static datadog.trace.plugin.csi.util.JavaParserUtils.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult;
import datadog.trace.plugin.csi.Extension;
import datadog.trace.plugin.csi.PluginApplication.Configuration;
import datadog.trace.plugin.csi.TypeResolver;
import datadog.trace.plugin.csi.impl.CallSiteSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AllArgsSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ArgumentSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification;
import datadog.trace.plugin.csi.util.CallSiteUtils;
import datadog.trace.plugin.csi.util.MethodType;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.annotation.Annotation;
import java.lang.invoke.CallSite;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

@SuppressWarnings("OptionalGetWithoutIsPresent")
public class IastExtension implements Extension {

  private static final String IAST_CALL_SITES_CLASS = "IastCallSites";
  static final String IAST_CALL_SITES_FQCN = "datadog.trace.api.iast." + IAST_CALL_SITES_CLASS;
  private static final String HAS_TELEMETRY_INTERFACE = IAST_CALL_SITES_CLASS + ".HasTelemetry";
  private static final String IAST_METRIC_COLLECTOR_CLASS = "IastMetricCollector";
  private static final String IAST_METRIC_COLLECTOR_FQCN =
      "datadog.trace.api.iast.telemetry." + IAST_METRIC_COLLECTOR_CLASS;

  private static final String IAST_METRIC_COLLECTOR_INTERNAL_NAME =
      IAST_METRIC_COLLECTOR_FQCN.replaceAll("\\.", "/");

  private static final String VERBOSITY_CLASS = "Verbosity";
  private static final String VERBOSITY_FQCN =
      "datadog.trace.api.iast.telemetry." + VERBOSITY_CLASS;

  private static final String IAST_METRIC_CLASS = "IastMetric";
  private static final String IAST_METRIC_FQCN =
      "datadog.trace.api.iast.telemetry." + IAST_METRIC_CLASS;

  private static final String IAST_METRIC_INTERNAL_NAME = IAST_METRIC_FQCN.replaceAll("\\.", "/");

  private static final String IAST_CSI_PACKAGE = "datadog.trace.api.iast.csi";

  private static final String HAS_DYNAMIC_SUPPORT_CLASS = "HasDynamicSupport";

  private static final String HAS_DYNAMIC_SUPPORT_FQCN =
      IAST_CSI_PACKAGE + "." + HAS_DYNAMIC_SUPPORT_CLASS;

  private static final String DYNAMIC_HELPER_CLASS_NAME = "DynamicHelper";

  private static final String SKIP_DYNAMIC_HELPERS_CLASS = "SkipDynamicHelpers";

  private static final String SKIP_DYNAMIC_HELPERS_FQCN =
      IAST_CSI_PACKAGE + "." + SKIP_DYNAMIC_HELPERS_CLASS;

  private static final String DYNAMIC_HELPER_FQCN =
      IAST_CSI_PACKAGE + "." + DYNAMIC_HELPER_CLASS_NAME;

  private static final String DYNAMIC_METHOD_SUFFIX = "$$Dynamic$";

  @Override
  public boolean appliesTo(@Nonnull final CallSiteSpecification spec) {
    return IAST_CALL_SITES_FQCN.equals(spec.getSpi().getClassName());
  }

  @Override
  public void apply(
      @Nonnull final Configuration configuration, @Nonnull final CallSiteResult result)
      throws Exception {
    final TypeResolver resolver = getTypeResolver(configuration);
    final CompilationUnit provider = parseJavaFile(resolver, result.getFile());
    final boolean telemetryAdded = addTelemetrySupport(configuration, resolver, result, provider);
    final CompilationUnit dynamicHelper = createDynamicHelper(resolver, result);
    if (dynamicHelper != null) {
      addDynamicHelperToProvider(provider, dynamicHelper);
    }
    if (telemetryAdded || dynamicHelper != null) {
      // save class in case of update
      provider.getStorage().get().save();
    }
  }

  /**
   * This method generates a custom java class that is able to bridge MethodHandle invocations with
   * static call sites configured with @CallSite.This, @CallSite.Argument ...
   */
  private CompilationUnit createDynamicHelper(
      final TypeResolver resolver, final CallSiteResult result) {
    final Class<?> callSite = resolver.resolveType(result.getSpecification().getClazz());
    final String dynamicCallSiteName = callSite.getSimpleName() + "Dynamic";
    final File target = new File(result.getFile().getParentFile(), dynamicCallSiteName + ".java");
    for (final Annotation annotation : callSite.getDeclaredAnnotations()) {
      if (SKIP_DYNAMIC_HELPERS_FQCN.equals(annotation.annotationType().getName())) {
        deleteFile(target);
        return null;
      }
    }
    // initial java file
    final CompilationUnit newJavaClass = new CompilationUnit();
    newJavaClass.setStorage(target.toPath());
    newJavaClass.setPackageDeclaration(callSite.getPackage().getName());
    newJavaClass.addImport(HAS_DYNAMIC_SUPPORT_FQCN);
    newJavaClass.addImport(DYNAMIC_HELPER_FQCN);
    newJavaClass.addImport(result.getSpecification().getClazz().getClassName());
    final ClassOrInterfaceDeclaration dynamicType =
        new ClassOrInterfaceDeclaration()
            .addModifier(Modifier.Keyword.PUBLIC)
            .setName(dynamicCallSiteName);
    newJavaClass.addType(dynamicType);
    dynamicType.addImplementedType(HAS_DYNAMIC_SUPPORT_CLASS);
    addAutoServiceAnnotation(dynamicType);
    final List<AdviceSpecification> advices = result.getSpecification().getAdvices();
    for (int i = 0; i < advices.size(); i++) {
      addDynamicCallSite(resolver, newJavaClass, i, advices.get(i));
    }
    // save new dynamic class
    newJavaClass.getStorage().get().save();
    return newJavaClass;
  }

  private void addDynamicHelperToProvider(
      final CompilationUnit callSiteProvider, final CompilationUnit dynamicHelper) {
    final Optional<PackageDeclaration> packageDecl = dynamicHelper.getPackageDeclaration();
    final ClassOrInterfaceDeclaration mainType = getPrimaryType(dynamicHelper);
    final String typeName =
        packageDecl
            .map(it -> it.getNameAsString() + "." + mainType.getNameAsString())
            .orElseGet(mainType::getNameAsString);
    final ClassOrInterfaceDeclaration providerType = getPrimaryType(callSiteProvider);
    final MethodDeclaration acceptMethod =
        providerType.getMethodsBySignature("accept", "Container").get(0);
    final BlockStmt body = acceptMethod.getBody().get().asBlockStmt();
    body.addStatement(
        new MethodCallExpr()
            .setScope(new NameExpr("container"))
            .setName("addHelpers")
            .addArgument(new StringLiteralExpr(typeName)));
  }

  private void addAutoServiceAnnotation(final ClassOrInterfaceDeclaration javaClass) {
    final NormalAnnotationExpr autoService = new NormalAnnotationExpr();
    autoService.setName(AUTO_SERVICE_FQDN);
    autoService.addPair(
        "value", new ClassExpr(new ClassOrInterfaceType().setName(HAS_DYNAMIC_SUPPORT_CLASS)));
    javaClass.addAnnotation(autoService);
  }

  private void addDynamicCallSite(
      final TypeResolver resolver,
      final CompilationUnit javaClass,
      final int index,
      final AdviceSpecification advice) {
    final String methodName = advice.getAdvice().getMethodName() + DYNAMIC_METHOD_SUFFIX + index;
    final ClassOrInterfaceDeclaration javaType = getPrimaryType(javaClass);
    final MethodDeclaration method =
        javaType.addMethod(methodName, Modifier.Keyword.STATIC, Modifier.Keyword.PUBLIC);
    method.setType(Object.class);
    method.addParameter(CallSite.class, "callSite");
    method.addParameter(Object[].class, "arguments");
    method.addThrownException(Throwable.class);
    addDynamicHelperAnnotation(resolver, javaClass, advice, method);
    addDynamicHelperBody(resolver, javaClass, advice, method);
  }

  private void addDynamicHelperAnnotation(
      final TypeResolver resolver,
      final CompilationUnit javaClass,
      final AdviceSpecification advice,
      final MethodDeclaration method) {
    final MethodType pointcut = advice.getPointcut();
    final NormalAnnotationExpr annotation = new NormalAnnotationExpr();
    final Executable executable = resolver.resolveMethod(pointcut);
    final Class<?> owner = executable.getDeclaringClass();
    final Class<?> returnType =
        executable instanceof Method ? ((Method) executable).getReturnType() : void.class;
    annotation.setName(DYNAMIC_HELPER_CLASS_NAME);
    annotation.addPair("owner", classExpr(javaClass, owner));
    annotation.addPair("method", new StringLiteralExpr(pointcut.getMethodName()));
    annotation.addPair("returnType", classExpr(javaClass, returnType));
    final NodeList<Expression> argumentTypes = new NodeList<>();
    for (final Class<?> param : executable.getParameterTypes()) {
      argumentTypes.add(classExpr(javaClass, param));
    }
    annotation.addPair("argumentTypes", new ArrayInitializerExpr(argumentTypes));
    method.addAnnotation(annotation);
  }

  private void addDynamicHelperBody(
      final TypeResolver resolver,
      final CompilationUnit javaClass,
      final AdviceSpecification advice,
      final MethodDeclaration method) {
    final BlockStmt body = new BlockStmt();
    final MethodCallExpr adviceCall = callAdviceMethod(resolver, javaClass, advice);
    final MethodCallExpr pointcutCall = callPointcutMethodViaCallSite();
    if (advice instanceof BeforeSpecification) {
      // try { advice.call() } catch (Throwable e) { LOG.error } return pointcut.call()
      body.addStatement(
          new TryStmt()
              .setTryBlock(new BlockStmt().addStatement(adviceCall))
              .setCatchClauses(new NodeList<>(dynamicCatchClause())));
      body.addStatement(new ReturnStmt().setExpression(pointcutCall));
    } else if (advice instanceof AroundSpecification) {
      // try { return advice.call() } catch (Throwable e) { LOG.error; return pointcut.call() }
      body.addStatement(
          new TryStmt()
              .setTryBlock(new BlockStmt().addStatement(new ReturnStmt().setExpression(adviceCall)))
              .setCatchClauses(new NodeList<>(dynamicCatchClause())));
      body.addStatement(new ReturnStmt().setExpression(pointcutCall));
    } else {
      // Object result = pointcut.call() try { advice.call(...result) }
      // catch (Throwable e) { LOG.error }
      // return result
      final Executable adviceExecutable = resolver.resolveMethod(advice.getAdvice());
      final Class<?>[] arguments = adviceExecutable.getParameterTypes();
      final Class<?> resultType = arguments[arguments.length - 1];
      final VariableDeclarator variable =
          new VariableDeclarator()
              .setType(Object.class)
              .setName("result")
              .setInitializer(callPointcutMethodViaCallSite());
      body.addStatement(
          new ExpressionStmt().setExpression(new VariableDeclarationExpr().addVariable(variable)));
      body.addStatement(
          new TryStmt()
              .setTryBlock(
                  new BlockStmt()
                      .addStatement(
                          adviceCall.addArgument(
                              castType(javaClass, resultType, variable.getNameAsExpression()))))
              .setCatchClauses(new NodeList<>(dynamicCatchClause())));
      body.addStatement(new ReturnStmt().setExpression(variable.getNameAsExpression()));
    }
    method.setBody(body);
  }

  private static CatchClause dynamicCatchClause() {
    final BlockStmt body =
        new BlockStmt()
            .addStatement(
                new MethodCallExpr()
                    .setScope(new NameExpr("LOG"))
                    .setName("error")
                    .addArgument(new StringLiteralExpr("Error handling dynamic invocation"))
                    .addArgument(new NameExpr("e")));
    return new CatchClause()
        .setParameter(new Parameter().setType(Throwable.class).setName("e"))
        .setBody(body);
  }

  private static MethodCallExpr callAdviceMethod(
      final TypeResolver resolver,
      final CompilationUnit javaClass,
      final AdviceSpecification spec) {
    final Executable pointcut = resolver.resolveMethod(spec.getPointcut());
    final Executable advice = resolver.resolveMethod(spec.getAdvice());
    final Class<?> adviceClass = resolver.resolveType(spec.getAdvice().getOwner());
    final MethodCallExpr methodCallExpr =
        new MethodCallExpr()
            .setScope(new NameExpr(adviceClass.getSimpleName()))
            .setName(spec.getAdvice().getMethodName());
    final AllArgsSpecification allArgs = spec.findAllArguments();
    if (allArgs != null) {
      if (allArgs.isIncludeThis()) {
        // add all arguments including this
        methodCallExpr.addArgument(new NameExpr("arguments"));
      } else {
        if (spec.includeThis()) {
          methodCallExpr.addArgument(popArrayElement(javaClass, advice.getParameterTypes()[0], 0));
        }
        // skip the first element
        final NodeList<Expression> values = new NodeList<>();
        for (int i = 0; i < pointcut.getParameterTypes().length; i++) {
          values.add(
              new ArrayAccessExpr().setName(new NameExpr("arguments")).setIndex(intLiteral(i + 1)));
        }
        methodCallExpr.addArgument(
            new ArrayCreationExpr()
                .setElementType(Object.class)
                .setInitializer(new ArrayInitializerExpr(values)));
      }
    } else {
      if (spec.includeThis()) {
        // this should be the first element
        methodCallExpr.addArgument(popArrayElement(javaClass, advice.getParameterTypes()[0], 0));
      }
      for (Map.Entry<Integer, CallSiteSpecification.ParameterSpecification> entry :
          spec.getParameters().entrySet()) {
        if (entry.getValue() instanceof ArgumentSpecification) {
          final ArgumentSpecification argument = (ArgumentSpecification) entry.getValue();
          final Class<?> type = advice.getParameterTypes()[entry.getKey()];
          // arguments are shifted 1 place
          methodCallExpr.addArgument(popArrayElement(javaClass, type, argument.getIndex() + 1));
        }
      }
    }
    return methodCallExpr;
  }

  private static Expression popArrayElement(
      final CompilationUnit javaClass, final Class<?> type, final int index) {
    return castType(
        javaClass,
        type,
        new ArrayAccessExpr().setName(new NameExpr("arguments")).setIndex(intLiteral(index)));
  }

  private static Expression castType(
      final CompilationUnit javaClass, final Class<?> type, final Expression expression) {
    javaClass.addImport(type);
    return new CastExpr().setType(type).setExpression(expression);
  }

  private static ClassExpr classExpr(final CompilationUnit javaClass, final Class<?> type) {
    javaClass.addImport(type);
    return new ClassExpr().setType(type);
  }

  private static MethodCallExpr callPointcutMethodViaCallSite() {
    final MethodCallExpr getTarget =
        new MethodCallExpr().setScope(new NameExpr("callSite")).setName("getTarget");
    return new MethodCallExpr()
        .setScope(getTarget)
        .setName("invokeWithArguments")
        .addArgument("arguments");
  }

  private boolean addTelemetrySupport(
      final Configuration configuration,
      final TypeResolver resolver,
      final CallSiteResult result,
      final CompilationUnit providerJavaFile)
      throws Exception {

    final ClassOrInterfaceDeclaration provider = getPrimaryType(providerJavaFile);
    if (implementsInterface(provider, HAS_TELEMETRY_INTERFACE)) {
      // already processed
      return false;
    }

    // find all the advices in the provider
    final Map<AdviceSpecification, LambdaExpr> advices = findAdvices(result, provider);

    // parse current call site class and fetch all metadata regarding telemetry
    final CompilationUnit originalCallSiteFile =
        findOriginalCallSite(configuration, resolver, result);
    final ClassOrInterfaceDeclaration originalCallSite = getPrimaryType(originalCallSiteFile);
    final AdviceMetadata globalMetadata = AdviceMetadata.findAdviceMetadata(originalCallSite);

    // add telemetry to each of the advices
    boolean hasTelemetry = false;
    for (final MethodDeclaration callSiteMethod : originalCallSite.getMethods()) {
      if (isCallSite(callSiteMethod)) {
        final AdviceMetadata methodMetadata = AdviceMetadata.findAdviceMetadata(callSiteMethod);
        final AdviceMetadata metaData = methodMetadata != null ? methodMetadata : globalMetadata;
        // if the call site class or the method has been annotated then apply the extension
        if (metaData != null) {
          final List<LambdaExpr> adviceLambdas = filterAdviceLambdas(advices, callSiteMethod);
          for (final LambdaExpr advice : adviceLambdas) {
            addTelemetryToAdvice(resolver, advice, metaData);
            hasTelemetry = true;
          }
        }
      }
    }

    if (hasTelemetry) {
      // add telemetry support to the provider class
      addTelemetryInterface(providerJavaFile);
    }
    return hasTelemetry;
  }

  private void addTelemetryInterface(final CompilationUnit javaClass) {
    javaClass.addImport(IAST_METRIC_COLLECTOR_FQCN);
    javaClass.addImport(IAST_METRIC_FQCN);
    javaClass.addImport(VERBOSITY_FQCN);
    final ClassOrInterfaceDeclaration mainType = getPrimaryType(javaClass);
    mainType.addImplementedType(HAS_TELEMETRY_INTERFACE);
    final FieldDeclaration verbosityField =
        mainType.addField(VERBOSITY_CLASS, "verbosity", Modifier.Keyword.PRIVATE);
    verbosityField
        .getVariable(0)
        .setInitializer(
            new FieldAccessExpr().setScope(new NameExpr(VERBOSITY_CLASS)).setName("OFF"));
    final MethodDeclaration enableTelemetry =
        mainType
            .addMethod("setVerbosity", Modifier.Keyword.PUBLIC)
            .addParameter(VERBOSITY_CLASS, "verbosity")
            .addAnnotation(Override.class);
    final BlockStmt enableTelemetryBody = new BlockStmt();
    enableTelemetryBody.addStatement(
        new AssignExpr()
            .setTarget(accessLocalField("verbosity"))
            .setValue(new NameExpr("verbosity")));
    enableTelemetry.setBody(enableTelemetryBody);
  }

  private CompilationUnit findOriginalCallSite(
      final Configuration configuration, final TypeResolver resolver, final CallSiteResult result)
      throws FileNotFoundException {
    final String originalClass = result.getSpecification().getClazz().getClassName();
    final String separator = File.separator.equals("\\") ? "\\\\" : File.separator;
    final String javaFile = originalClass.replaceAll("\\.", separator);
    return parseSourceFile(configuration, resolver, javaFile);
  }

  private void addTelemetryToAdvice(
      final TypeResolver resolver, final LambdaExpr adviceLambda, final AdviceMetadata metaData) {
    final BlockStmt lambdaBody = adviceLambda.getBody().asBlockStmt();
    final String metric = getMetricName(metaData);
    final String tagValue = getMetricTagValue(resolver, metaData);
    final String instrumentedMetric = "INSTRUMENTED_" + metric;
    final IfStmt instrumentedStatement =
        new IfStmt()
            .setCondition(isEnabledCondition(instrumentedMetric))
            .setThenStmt(
                new BlockStmt()
                    .addStatement(addTelemetryCollectorMethod(instrumentedMetric, tagValue)));
    lambdaBody.addStatement(0, instrumentedStatement);
    final String executedMetric = "EXECUTED_" + metric;
    final IfStmt executedStatement =
        new IfStmt()
            .setCondition(isEnabledCondition(executedMetric))
            .setThenStmt(addTelemetryCollectorByteCode(executedMetric, tagValue));
    lambdaBody.addStatement(1, executedStatement);
  }

  private static Expression isEnabledCondition(final String metric) {
    return new MethodCallExpr()
        .setScope(new FieldAccessExpr().setScope(new NameExpr(IAST_METRIC_CLASS)).setName(metric))
        .setName("isEnabled")
        .addArgument(accessLocalField("verbosity"));
  }

  private static MethodCallExpr addTelemetryCollectorMethod(
      final String metric, final String tagValue) {
    final MethodCallExpr method =
        new MethodCallExpr()
            .setScope(new NameExpr(IAST_METRIC_COLLECTOR_CLASS))
            .setName("add")
            .addArgument(
                new FieldAccessExpr().setScope(new NameExpr(IAST_METRIC_CLASS)).setName(metric));
    if (tagValue != null) {
      method.addArgument(new StringLiteralExpr(tagValue));
    }
    method.addArgument(intLiteral(1));
    return method;
  }

  private static BlockStmt addTelemetryCollectorByteCode(
      final String metric, final String tagValue) {
    final BlockStmt stmt = new BlockStmt();
    // this code generates the java source code needed to provide the bytecode for the statement
    // IastTelemetryCollector.add(${metric}, 1); or IastTelemetryCollector.add(${metric}, ${tag},
    // 1);
    stmt.addStatement(
        new MethodCallExpr()
            .setScope(new NameExpr("handler"))
            .setName("field")
            .addArgument(
                new FieldAccessExpr().setScope(new NameExpr(OPCODES_FQDN)).setName("GETSTATIC"))
            .addArgument(new StringLiteralExpr(IAST_METRIC_INTERNAL_NAME))
            .addArgument(new StringLiteralExpr(metric))
            .addArgument(new StringLiteralExpr("L" + IAST_METRIC_INTERNAL_NAME + ";")));
    if (tagValue != null) {
      stmt.addStatement(
          new MethodCallExpr()
              .setScope(new NameExpr("handler"))
              .setName("loadConstant")
              .addArgument(new StringLiteralExpr(tagValue)));
    }
    stmt.addStatement(
        new MethodCallExpr()
            .setScope(new NameExpr("handler"))
            .setName("instruction")
            .addArgument(
                new FieldAccessExpr().setScope(new NameExpr(OPCODES_FQDN)).setName("ICONST_1")));
    final String descriptor =
        tagValue != null
            ? "(L" + IAST_METRIC_INTERNAL_NAME + ";Ljava/lang/String;I)V"
            : "(L" + IAST_METRIC_INTERNAL_NAME + ";I)V";
    stmt.addStatement(
        new MethodCallExpr()
            .setScope(new NameExpr("handler"))
            .setName("method")
            .addArgument(
                new FieldAccessExpr().setScope(new NameExpr(OPCODES_FQDN)).setName("INVOKESTATIC"))
            .addArgument(new StringLiteralExpr(IAST_METRIC_COLLECTOR_INTERNAL_NAME))
            .addArgument(new StringLiteralExpr("add"))
            .addArgument(new StringLiteralExpr(descriptor))
            .addArgument(new BooleanLiteralExpr(false)));
    return stmt;
  }

  private static String getMetricName(final AdviceMetadata metaData) {
    final AnnotationExpr kind = metaData.getKind();
    return kind.getName().getId().toUpperCase();
  }

  private static String getMetricTagValue(
      final TypeResolver resolver, final AdviceMetadata metadata) {
    if (metadata.getTag() == null) {
      return null;
    }
    final Expression tag = metadata.getTag();
    if (tag.isStringLiteralExpr()) {
      return tag.asStringLiteralExpr().getValue();
    } else {
      return getFieldValue(resolver, tag.asFieldAccessExpr());
    }
  }

  private static String getFieldValue(final TypeResolver resolver, final FieldAccessExpr tag) {
    final FieldAccessExpr fieldAccessExpr = tag.asFieldAccessExpr();
    final ResolvedFieldDeclaration value = fieldAccessExpr.resolve().asField();
    try {
      final Field field = getField(value);
      field.setAccessible(true);
      return (String) field.get(field.getDeclaringClass());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static Field getField(final ResolvedFieldDeclaration resolved) {
    try {
      final Field field = resolved.getClass().getDeclaredField("field");
      field.setAccessible(true);
      return (Field) field.get(resolved);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Find all advice lambdas in the generated call site provider */
  private static Map<AdviceSpecification, LambdaExpr> findAdvices(
      final CallSiteResult result, final ClassOrInterfaceDeclaration callSiteProvider) {
    final MethodDeclaration acceptMethod =
        callSiteProvider.getMethodsBySignature("accept", "Container").get(0);
    final BlockStmt body = acceptMethod.getBody().get().asBlockStmt();
    final List<MethodCallExpr> addAdviceMethods =
        body.getStatements().stream()
            .filter(IastExtension::isAddAdviceMethodCall)
            .map(it -> it.asExpressionStmt().getExpression().asMethodCallExpr())
            .collect(Collectors.toList());
    return result.getSpecification().getAdvices().stream()
        .collect(
            Collectors.toMap(
                Function.identity(), spec -> findAdviceLambda(spec, addAdviceMethods)));
  }

  /**
   * Return only the lambdas that match the specified call site method by looking into the
   * signatures
   */
  private static List<LambdaExpr> filterAdviceLambdas(
      final Map<AdviceSpecification, LambdaExpr> advices, final MethodDeclaration callSiteMethod) {

    final List<LambdaExpr> result = new ArrayList<>();
    for (final Map.Entry<AdviceSpecification, LambdaExpr> entry : advices.entrySet()) {
      final AdviceSpecification spec = entry.getKey();
      final MethodType methodType = spec.getAdvice();
      if (!methodType.getMethodName().equals(callSiteMethod.getNameAsString())) {
        continue;
      }
      // java parser has issues with inner classes and descriptors, remove the dollar to do the
      // matching)
      final String descriptor = methodType.getMethodType().getDescriptor().replaceAll("\\$", "/");
      if (!descriptor.equals(callSiteMethod.toDescriptor())) {
        continue;
      }
      result.add(entry.getValue());
    }
    return result;
  }

  private static boolean isAddAdviceMethodCall(final Statement statement) {
    if (!statement.isExpressionStmt()) {
      return false;
    }
    final Expression expression = statement.asExpressionStmt().getExpression();
    if (!expression.isMethodCallExpr()) {
      return false;
    }
    final MethodCallExpr methodCall = expression.asMethodCallExpr();
    if (!methodCall.getScope().get().toString().equals("container")) {
      return false;
    }
    return methodCall.getNameAsString().equals("addAdvice");
  }

  private static LambdaExpr findAdviceLambda(
      final AdviceSpecification spec, final List<MethodCallExpr> addAdvices) {
    final MethodType pointcut = spec.getPointcut();
    for (final MethodCallExpr add : addAdvices) {
      final NodeList<Expression> arguments = add.getArguments();
      final String owner = arguments.get(0).asStringLiteralExpr().asString();
      if (!owner.equals(pointcut.getOwner().getInternalName())) {
        continue;
      }
      final String method = arguments.get(1).asStringLiteralExpr().asString();
      if (!method.equals(pointcut.getMethodName())) {
        continue;
      }
      final String description = arguments.get(2).asStringLiteralExpr().asString();
      if (!description.equals(pointcut.getMethodType().getDescriptor())) {
        continue;
      }
      final LambdaExpr lambda = arguments.get(3).asLambdaExpr();
      final Optional<String> signature =
          add.getParentNode().get().getComment().map(Comment::getContent).map(String::trim);
      if (signature.isPresent()) {
        if (signature.get().equals(spec.getAdvice().toString())) {
          return lambda;
        }
      } else {
        // can happen with previous versions
        return lambda;
      }
    }
    throw new IllegalArgumentException("Cannot find lambda expression for pointcut " + pointcut);
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

  private static Expression getAnnotationExpression(final AnnotationExpr expr) {
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

  private static class AdviceMetadata {
    private final AnnotationExpr kind;
    private final Expression tag;

    private AdviceMetadata(final AnnotationExpr kind, final Expression tag) {
      this.kind = kind;
      this.tag = tag;
    }

    private static AdviceMetadata findAdviceMetadata(final BodyDeclaration<?> target) {
      return target.getAnnotations().stream()
          .filter(AdviceMetadata::isAdviceAnnotation)
          .map(
              annotation -> {
                final Expression tag = getAnnotationExpression(annotation);
                return new AdviceMetadata(annotation, tag);
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

    public AnnotationExpr getKind() {
      return kind;
    }

    public Expression getTag() {
      return tag;
    }
  }
}
