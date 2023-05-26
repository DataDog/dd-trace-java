package datadog.trace.plugin.csi.impl;

import static com.github.javaparser.ast.Modifier.Keyword.PUBLIC;
import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver;
import static datadog.trace.plugin.csi.util.CallSiteConstants.AUTO_SERVICE_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.CALL_SITE_ADVICE_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.CALL_SITE_ADVICE_FQCN;
import static datadog.trace.plugin.csi.util.CallSiteConstants.HANDLE_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.HAS_FLAGS_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.HAS_HELPERS_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.HAS_MIN_JAVA_VERSION_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.INVOKE_ADVICE_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.INVOKE_DYNAMIC_ADVICE_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.METHOD_HANDLER_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.OPCODES_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.POINTCUT_FQCN;
import static datadog.trace.plugin.csi.util.CallSiteConstants.POINTCUT_TYPE;
import static datadog.trace.plugin.csi.util.CallSiteConstants.STACK_DUP_MODE_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.TYPE_RESOLVER;
import static datadog.trace.plugin.csi.util.CallSiteUtils.capitalize;
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType;
import static datadog.trace.plugin.csi.util.CallSiteUtils.deleteFile;
import static datadog.trace.plugin.csi.util.JavaParserUtils.intLiteral;
import static datadog.trace.plugin.csi.util.JavaParserUtils.singleStatementMethod;
import static datadog.trace.plugin.csi.util.JavaParserUtils.stringLiteralMethod;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import datadog.trace.plugin.csi.AdviceGenerator;
import datadog.trace.plugin.csi.AdvicePointcutParser;
import datadog.trace.plugin.csi.HasErrors;
import datadog.trace.plugin.csi.TypeResolver;
import datadog.trace.plugin.csi.ValidationContext;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AfterSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AllArgsSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification;
import datadog.trace.plugin.csi.util.ErrorCode;
import datadog.trace.plugin.csi.util.MethodType;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

/**
 * Implementation of {@link AdviceGenerator} that uses Freemarker to build the Java files with the
 * {@link datadog.trace.agent.tooling.csi.CallSiteAdvice} implementation
 */
public class AdviceGeneratorImpl implements AdviceGenerator {

  private final File targetFolder;
  private final AdvicePointcutParser pointcutParser;
  private final TypeResolver typeResolver;

  public AdviceGeneratorImpl(
      @Nonnull final File targetFolder, @Nonnull final AdvicePointcutParser pointcutParser) {
    this(targetFolder, pointcutParser, typeResolver());
  }

  public AdviceGeneratorImpl(
      @Nonnull final File targetFolder,
      @Nonnull final AdvicePointcutParser pointcutParser,
      @Nonnull final TypeResolver typeResolver) {
    this.targetFolder = targetFolder;
    this.pointcutParser = pointcutParser;
    this.typeResolver = typeResolver;
  }

  @Override
  @Nonnull
  public CallSiteResult generate(@Nonnull final CallSiteSpecification spec) {
    final CallSiteResult result = new CallSiteResult(spec);
    result.addContextProperty(TYPE_RESOLVER, typeResolver);
    try {
      spec.validate(result);
      if (result.isSuccess()) {
        Map<String, List<AdviceSpecification>> advices = groupAdvicesByMethod(spec);
        for (List<AdviceSpecification> list : advices.values()) {
          final boolean unique = list.size() == 1;
          for (int i = 0; i < list.size(); i++) {
            final AdviceSpecification advice = list.get(i);
            final String className =
                String.format(
                    "%s%s%s",
                    spec.getClazz().getClassName(),
                    capitalize(advice.getAdvice().getMethodName()),
                    unique ? "" : i);
            result.addAdvice(generateAdviceJavaFile(spec, advice, classNameToType(className)));
          }
        }
      }
    } catch (Throwable e) {
      handleThrowable(result, e);
    }
    return result;
  }

  private Map<String, List<AdviceSpecification>> groupAdvicesByMethod(
      @Nonnull final CallSiteSpecification spec) {
    return spec.getAdvices().stream()
        .collect(Collectors.groupingBy(advice -> advice.getAdvice().getMethodName().toLowerCase()));
  }

  @SuppressWarnings("OptionalGetWithoutIsPresent")
  private AdviceResult generateAdviceJavaFile(
      @Nonnull final CallSiteSpecification callSite,
      @Nonnull final AdviceSpecification spec,
      @Nonnull final Type advice) {
    final File javaFile = new File(targetFolder, advice.getInternalName() + ".java");
    final AdviceResult result = new AdviceResult(spec, javaFile);
    result.addContextProperty(TYPE_RESOLVER, typeResolver);
    try {
      spec.parseSignature(pointcutParser);
      spec.validate(result);
      if (!result.isSuccess()) {
        deleteFile(javaFile);
        return result;
      }
      final CompilationUnit javaClass = buildJavaClass(callSite, spec, advice, javaFile);
      javaClass.getStorage().get().save();
    } catch (Throwable e) {
      deleteFile(javaFile);
      handleThrowable(result, e);
    }
    return result;
  }

  private static CompilationUnit buildJavaClass(
      final CallSiteSpecification callSite,
      final AdviceSpecification spec,
      final Type advice,
      final File javaFile) {
    final CompilationUnit javaClass = new CompilationUnit();
    javaClass.setStorage(javaFile.toPath());
    final String packageName = getPackageName(advice);
    if (packageName != null) {
      javaClass.setPackageDeclaration(getPackageName(advice));
    }
    javaClass.addImport(OPCODES_CLASS);
    javaClass.addImport(HANDLE_CLASS);
    javaClass.addImport(CALL_SITE_ADVICE_FQCN);
    javaClass.addImport(POINTCUT_FQCN);
    javaClass.addImport(callSite.getSpi().getClassName());
    final ClassOrInterfaceDeclaration type = adviceType(javaClass, advice);
    autoService(callSite, type);
    pointCut(spec, type);
    advice(spec, type);
    flags(spec, type);
    helpers(callSite, type);
    minJavaVersion(callSite, spec, type);
    return javaClass;
  }

  private static ClassOrInterfaceDeclaration adviceType(
      final CompilationUnit javaClass, final Type advice) {
    final ClassOrInterfaceDeclaration type = new ClassOrInterfaceDeclaration();
    type.setModifier(PUBLIC, true);
    type.setName(getClassName(advice));
    javaClass.addType(type);
    return type;
  }

  private static void autoService(
      final CallSiteSpecification callSite, final ClassOrInterfaceDeclaration javaClass) {
    final NormalAnnotationExpr autoService = new NormalAnnotationExpr();
    autoService.setName(AUTO_SERVICE_CLASS);
    autoService.addPair(
        "value",
        new ClassExpr(new ClassOrInterfaceType().setName(getClassName(callSite.getSpi(), false))));
    final String spiClass = callSite.getSpi().getClassName();
    javaClass.addAnnotation(autoService);
    if (!CALL_SITE_ADVICE_CLASS.equals(spiClass)) {
      javaClass.addImplementedType(spiClass);
    }
  }

  private static void pointCut(
      final AdviceSpecification spec, final ClassOrInterfaceDeclaration javaClass) {
    final MethodType pointcut = spec.getPointcut();
    javaClass.addImplementedType(POINTCUT_TYPE);
    javaClass.addMember(singleStatementMethod("pointcut", POINTCUT_TYPE, new ThisExpr(), true));
    javaClass.addMember(stringLiteralMethod("type", pointcut.getOwner().getInternalName(), true));
    javaClass.addMember(stringLiteralMethod("method", pointcut.getMethodName(), true));
    javaClass.addMember(
        stringLiteralMethod("descriptor", pointcut.getMethodType().getDescriptor(), true));
  }

  private static void advice(
      final AdviceSpecification spec, final ClassOrInterfaceDeclaration javaClass) {
    javaClass.addImplementedType(CALL_SITE_ADVICE_CLASS);
    final MethodDeclaration apply = javaClass.addMethod("apply", PUBLIC);
    apply.addAnnotation(Override.class);
    apply.setType(void.class);
    if (spec.isInvokeDynamic()) {
      invokeDynamicAdvice(javaClass, apply);
    } else {
      invokeAdvice(javaClass, apply);
    }
    final BlockStmt body = new BlockStmt();
    apply.setBody(body);
    if (spec instanceof BeforeSpecification) {
      writeStackOperations(spec, body);
      writeAdviceMethodCall(spec, body);
      writeOriginalMethodCall(spec, body);
    } else if (spec instanceof AfterSpecification) {
      writeStackOperations(spec, body);
      writeOriginalMethodCall(spec, body);
      writeAdviceMethodCall(spec, body);
    } else {
      writeAdviceMethodCall(spec, body);
    }
  }

  private static void flags(
      final AdviceSpecification spec, final ClassOrInterfaceDeclaration javaClass) {
    if (spec.isComputeMaxStack()) {
      javaClass.addImplementedType(HAS_FLAGS_CLASS);
      final MethodDeclaration flags = javaClass.addMethod("flags", PUBLIC);
      flags.addAnnotation(Override.class);
      flags.setType(int.class);
      final BlockStmt body = new BlockStmt();
      body.addStatement(new ReturnStmt(new NameExpr("COMPUTE_MAX_STACK")));
      flags.setBody(body);
    }
  }

  private static void helpers(
      final CallSiteSpecification spec, final ClassOrInterfaceDeclaration javaClass) {
    if (spec.getHelpers().length > 0) {
      javaClass.addImplementedType(HAS_HELPERS_CLASS);
      final MethodDeclaration helpers = javaClass.addMethod("helperClassNames", PUBLIC);
      helpers.addAnnotation(Override.class);
      helpers.setType(String[].class);
      final BlockStmt body = new BlockStmt();
      final List<Expression> helpersValues =
          Arrays.stream(spec.getHelpers())
              .map(helper -> new StringLiteralExpr(helper.getClassName()))
              .collect(Collectors.toList());
      body.addStatement(
          new ReturnStmt(
              new ArrayCreationExpr()
                  .setElementType(String.class)
                  .setInitializer(
                      new ArrayInitializerExpr().setValues(new NodeList<>(helpersValues)))));
      helpers.setBody(body);
    }
  }

  private static void minJavaVersion(
      final CallSiteSpecification spec,
      final AdviceSpecification advice,
      final ClassOrInterfaceDeclaration javaClass) {
    int minJavaVersion = spec.getMinJavaVersion();
    if (advice.isInvokeDynamic()) {
      minJavaVersion = Math.max(minJavaVersion, 9);
    }
    if (0 <= minJavaVersion) {
      javaClass.addImplementedType(HAS_MIN_JAVA_VERSION_CLASS);
      final MethodDeclaration flags = javaClass.addMethod("minJavaVersion", PUBLIC);
      flags.addAnnotation(Override.class);
      flags.setType(int.class);
      final BlockStmt body = new BlockStmt();
      body.addStatement(
          new ReturnStmt(new IntegerLiteralExpr(Integer.toString(spec.getMinJavaVersion()))));
      flags.setBody(body);
    }
  }

  private static void writeStackOperations(final AdviceSpecification advice, final BlockStmt body) {
    final boolean instanceMethod = !advice.isStaticPointcut();
    final AllArgsSpecification allArgsSpec = advice.findAllArguments();
    if (allArgsSpec == null && advice.isPositionalArguments()) {
      final List<Expression> parameterIndicesValues =
          advice
              .getArguments()
              .sorted()
              .map(argSpec -> intLiteral(argSpec.getIndex()))
              .collect(Collectors.toList());
      final VariableDeclarator parameterIndices =
          new VariableDeclarator()
              .setName("parameterIndices")
              .setType(new ArrayType(new PrimitiveType(PrimitiveType.Primitive.INT)))
              .setInitializer(
                  new ArrayCreationExpr()
                      .setElementType(int.class)
                      .setInitializer(
                          new ArrayInitializerExpr()
                              .setValues(new NodeList<>(parameterIndicesValues))));
      body.addStatement(new VariableDeclarationExpr().addVariable(parameterIndices));
      final MethodCallExpr dupMethod = new MethodCallExpr().setScope(new NameExpr("handler"));
      if (advice.includeThis()) {
        dupMethod.setName("dupInvoke");
        dupMethod.addArgument(new NameExpr("owner"));
        dupMethod.addArgument(new NameExpr("descriptor"));
        dupMethod.addArgument(new NameExpr(parameterIndices.getNameAsString()));
      } else {
        dupMethod.setName("dupParameters");
        dupMethod.addArgument(new NameExpr("descriptor"));
        dupMethod.addArgument(new NameExpr("parameterIndices"));
        dupMethod.addArgument(instanceMethod ? new NameExpr("owner") : new NullLiteralExpr());
      }
      body.addStatement(dupMethod);
    } else {
      final MethodCallExpr dupMethod = new MethodCallExpr().setScope(new NameExpr("handler"));
      if (advice.includeThis()) {
        dupMethod.setName("dupInvoke");
        dupMethod.addArgument(new NameExpr("owner"));
        dupMethod.addArgument(new NameExpr("descriptor"));
      } else {
        dupMethod.setName("dupParameters");
        dupMethod.addArgument(new NameExpr("descriptor"));
      }
      String mode = "COPY";
      if (allArgsSpec != null) {
        if (advice instanceof AfterSpecification) {
          mode = advice.isConstructor() ? "PREPEND_ARRAY_CTOR" : "PREPEND_ARRAY";
        } else {
          mode = "APPEND_ARRAY";
        }
      }
      dupMethod.addArgument(
          new FieldAccessExpr()
              .setScope(new TypeExpr(new ClassOrInterfaceType().setName(STACK_DUP_MODE_CLASS)))
              .setName(mode));
      body.addStatement(dupMethod);
    }
  }

  private static void writeAdviceMethodCall(
      final AdviceSpecification advice, final BlockStmt body) {
    final MethodType method = advice.getAdvice();
    if (advice instanceof AroundSpecification && advice.isInvokeDynamic()) {
      final Expression newHandle =
          new ObjectCreationExpr()
              .setType("Handle")
              .addArgument(opCode("H_INVOKESTATIC"))
              .addArgument(new StringLiteralExpr(method.getOwner().getInternalName()))
              .addArgument(new StringLiteralExpr(method.getMethodName()))
              .addArgument(new StringLiteralExpr(method.getMethodType().getDescriptor()))
              .addArgument(new BooleanLiteralExpr(false));
      final MethodCallExpr invokeDynamic =
          new MethodCallExpr()
              .setScope(new NameExpr("handler"))
              .setName("invokeDynamic")
              .addArgument(new NameExpr("name"))
              .addArgument(new NameExpr("descriptor"))
              .addArgument(newHandle)
              .addArgument(new NameExpr("bootstrapMethodArguments"));
      body.addStatement(invokeDynamic);
    } else {
      if (advice.isInvokeDynamic() && advice.findInvokeDynamicConstants() != null) {
        // we should add the boostrap method constants before the method call
        final MethodCallExpr loadConstantArray =
            new MethodCallExpr()
                .setScope(new NameExpr("handler"))
                .setName("loadConstantArray")
                .addArgument(new NameExpr("bootstrapMethodArguments"));
        body.addStatement(loadConstantArray);
      }
      final MethodCallExpr invokeStatic =
          new MethodCallExpr()
              .setScope(new NameExpr("handler"))
              .setName("method")
              .addArgument(opCode("INVOKESTATIC"))
              .addArgument(new StringLiteralExpr(method.getOwner().getInternalName()))
              .addArgument(new StringLiteralExpr(method.getMethodName()))
              .addArgument(new StringLiteralExpr(method.getMethodType().getDescriptor()))
              .addArgument(new BooleanLiteralExpr(false));
      body.addStatement(invokeStatic);
    }
    if (requiresCast(advice)) {
      final MethodType pointcut = advice.getPointcut();
      final Type expectedReturn =
          pointcut.isConstructor() ? pointcut.getOwner() : pointcut.getMethodType().getReturnType();
      if (!expectedReturn.equals(method.getMethodType().getReturnType())) {
        body.addStatement(
            new MethodCallExpr()
                .setScope(new NameExpr("handler"))
                .setName("instruction")
                .addArgument(opCode("CHECKCAST"))
                .addArgument(new StringLiteralExpr(expectedReturn.getInternalName())));
      }
    }
  }

  private static boolean requiresCast(final AdviceSpecification advice) {
    if (advice instanceof AroundSpecification) {
      return true; // around always replaces the original method call
    }
    if (advice instanceof AfterSpecification) {
      return !advice.isInvokeDynamic(); // dynamic invokes original method call returns CallSite
    }
    return false;
  }

  private static void writeOriginalMethodCall(
      final AdviceSpecification advice, final BlockStmt body) {
    final MethodCallExpr invoke = new MethodCallExpr().setScope(new NameExpr("handler"));
    if (advice.isInvokeDynamic()) {
      invoke
          .setName("invokeDynamic")
          .addArgument(new NameExpr("name"))
          .addArgument(new NameExpr("descriptor"))
          .addArgument(new NameExpr("bootstrapMethodHandle"))
          .addArgument(new NameExpr("bootstrapMethodArguments"));
    } else {
      invoke
          .setName("method")
          .addArgument(new NameExpr("opcode"))
          .addArgument(new NameExpr("owner"))
          .addArgument(new NameExpr("name"))
          .addArgument(new NameExpr("descriptor"))
          .addArgument(new NameExpr("isInterface"));
    }
    body.addStatement(invoke);
  }

  private static void invokeAdvice(
      final ClassOrInterfaceDeclaration javaClass, final MethodDeclaration apply) {
    javaClass.addImplementedType(INVOKE_ADVICE_CLASS);
    apply.addParameter(METHOD_HANDLER_CLASS, "handler");
    apply.addParameter(int.class, "opcode");
    apply.addParameter(String.class, "owner");
    apply.addParameter(String.class, "name");
    apply.addParameter(String.class, "descriptor");
    apply.addParameter(boolean.class, "isInterface");
  }

  private static void invokeDynamicAdvice(
      final ClassOrInterfaceDeclaration javaClass, final MethodDeclaration apply) {
    javaClass.addImplementedType(INVOKE_DYNAMIC_ADVICE_CLASS);
    apply.addParameter(METHOD_HANDLER_CLASS, "handler");
    apply.addParameter(String.class, "name");
    apply.addParameter(String.class, "descriptor");
    apply.addParameter(HANDLE_CLASS, "bootstrapMethodHandle");
    apply.addParameter(
        new Parameter().setVarArgs(true).setType(Object.class).setName("bootstrapMethodArguments"));
  }

  private static Expression opCode(final String opCode) {
    return new FieldAccessExpr()
        .setScope(new TypeExpr(new ClassOrInterfaceType().setName("Opcodes")))
        .setName(opCode);
  }

  private static void handleThrowable(
      @Nonnull final ValidationContext container, @Nonnull final Throwable t) {
    if (t instanceof HasErrors) {
      ((HasErrors) t).getErrors().forEach(container::addError);
    } else {
      container.addError(t, ErrorCode.UNCAUGHT_ERROR);
    }
  }

  private static String getPackageName(final Type type) {
    final String className = type.getClassName();
    final int index = type.getClassName().lastIndexOf('.');
    return index >= 0 ? className.substring(0, index) : null;
  }

  private static String getClassName(final Type type) {
    return getClassName(type, true);
  }

  private static String getClassName(final Type type, final boolean definition) {
    final String className = type.getClassName();
    final int index = type.getClassName().lastIndexOf('.');
    final String result = index >= 0 ? className.substring(index + 1) : className;
    return definition ? result : result.replace('$', '.');
  }
}
