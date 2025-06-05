package datadog.trace.plugin.csi.impl;

import static com.github.javaparser.ast.Modifier.Keyword.PUBLIC;
import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver;
import static datadog.trace.plugin.csi.util.CallSiteConstants.ADVICE_TYPE_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.AUTO_SERVICE_FQDN;
import static datadog.trace.plugin.csi.util.CallSiteConstants.CALL_SITES_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.CALL_SITES_FQCN;
import static datadog.trace.plugin.csi.util.CallSiteConstants.CALL_SITE_ADVICE_FQCN;
import static datadog.trace.plugin.csi.util.CallSiteConstants.HANDLE_FQDN;
import static datadog.trace.plugin.csi.util.CallSiteConstants.HAS_ENABLED_PROPERTY_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.METHOD_HANDLER_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.OPCODES_FQDN;
import static datadog.trace.plugin.csi.util.CallSiteConstants.STACK_DUP_MODE_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.TYPE_RESOLVER;
import static datadog.trace.plugin.csi.util.CallSiteUtils.deleteFile;
import static datadog.trace.plugin.csi.util.JavaParserUtils.getPrimaryType;
import static datadog.trace.plugin.csi.util.JavaParserUtils.intLiteral;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
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
import datadog.trace.plugin.csi.impl.CallSiteSpecification.Enabled;
import datadog.trace.plugin.csi.util.ErrorCode;
import datadog.trace.plugin.csi.util.MethodType;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

/**
 * Implementation of {@link AdviceGenerator} that uses Freemarker to build the Java files with the
 * {@link datadog.trace.agent.tooling.csi.CallSiteAdvice} implementation
 */
@SuppressWarnings("OptionalGetWithoutIsPresent")
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
    final Type callSitesType =
        Type.getType(String.format("L%ss;", spec.getClazz().getInternalName()));
    final File callSitesFile = new File(targetFolder, callSitesType.getInternalName() + ".java");
    final CallSiteResult result = new CallSiteResult(spec, callSitesFile);
    result.addContextProperty(TYPE_RESOLVER, typeResolver);
    try {
      spec.validate(result);
      if (result.isSuccess()) {
        final CompilationUnit javaClass = buildJavaClass(spec, callSitesType, callSitesFile);
        final ClassOrInterfaceDeclaration mainType = getPrimaryType(javaClass);
        final BlockStmt acceptBody = new BlockStmt();
        mainType
            .addMethod("accept", PUBLIC)
            .setType(void.class)
            .setBody(acceptBody)
            .addAnnotation(Override.class.getName())
            .setParameters(
                new NodeList<>(new Parameter().setName("container").setType("Container")));
        if (spec.getEnabled() != null) {
          addEnabledCheck(mainType, spec.getEnabled());
        }
        addHelpersInvocation(spec.getHelpers(), acceptBody);
        for (final AdviceSpecification advice : spec.getAdvices()) {
          try {
            advice.parseSignature(pointcutParser);
            advice.validate(result);
            if (result.isSuccess()) {
              addAdviceLambda(advice, acceptBody);
            }
          } catch (Throwable e) {
            handleThrowable(result, e);
          }
        }
        javaClass.getStorage().get().save();
      }
    } catch (Throwable e) {
      handleThrowable(result, e);
    }
    if (!result.isSuccess()) {
      deleteFile(callSitesFile);
    }
    return result;
  }

  private static CompilationUnit buildJavaClass(
      final CallSiteSpecification spec, final Type type, final File javaFile) {
    final CompilationUnit javaClass = new CompilationUnit();
    javaClass.setStorage(javaFile.toPath());
    final String packageName = getPackageName(spec.getClazz());
    if (packageName != null) {
      javaClass.setPackageDeclaration(getPackageName(spec.getClazz()));
    }
    javaClass.addImport(OPCODES_FQDN);
    javaClass.addImport(HANDLE_FQDN);
    javaClass.addImport(CALL_SITES_FQCN);
    javaClass.addImport(CALL_SITE_ADVICE_FQCN + ".*");
    final ClassOrInterfaceDeclaration javaType = callSitesType(javaClass, spec, type);
    addAutoServiceAnnotation(javaType, spec);
    return javaClass;
  }

  private static ClassOrInterfaceDeclaration callSitesType(
      final CompilationUnit javaClass, final CallSiteSpecification callSite, final Type advice) {
    final ClassOrInterfaceDeclaration type = new ClassOrInterfaceDeclaration();
    type.setModifier(PUBLIC, true);
    type.setName(getClassName(advice));
    type.addImplementedType(CALL_SITES_CLASS);
    for (final Type spi : callSite.getSpi()) {
      if (!CALL_SITES_FQCN.equals(spi.getClassName())) {
        javaClass.addImport(spi.getClassName());
        type.addImplementedType(getClassName(spi, false));
      }
    }
    javaClass.addType(type);
    return type;
  }

  private static void addAutoServiceAnnotation(
      final ClassOrInterfaceDeclaration javaClass, final CallSiteSpecification callSite) {
    final NormalAnnotationExpr autoService = new NormalAnnotationExpr();
    autoService.setName(AUTO_SERVICE_FQDN);
    final Type[] spiTypes = callSite.getSpi();
    final List<Expression> spiExprs = new ArrayList<>(spiTypes.length);
    for (final Type spi : spiTypes) {
      spiExprs.add(new ClassExpr(new ClassOrInterfaceType().setName(getClassName(spi, false))));
    }
    autoService.addPair("value", new ArrayInitializerExpr().setValues(new NodeList<>(spiExprs)));
    javaClass.addAnnotation(autoService);
  }

  private void addAdviceLambda(
      @Nonnull final AdviceSpecification spec, @Nonnull final BlockStmt body) {
    final MethodType pointCut = spec.getPointcut();
    final BlockStmt adviceBody = new BlockStmt();
    final Expression advice;
    final String type;
    if (spec.isInvokeDynamic()) {
      advice = invokeDynamicAdviceSignature(adviceBody);
    } else {
      advice = invokeAdviceSignature(adviceBody);
    }
    if (spec instanceof BeforeSpecification) {
      type = "BEFORE";
      writeStackOperations(spec, adviceBody);
      writeAdviceMethodCall(spec, adviceBody);
      writeOriginalMethodCall(spec, adviceBody);
    } else if (spec instanceof AfterSpecification) {
      type = "AFTER";
      writeStackOperations(spec, adviceBody);
      writeOriginalMethodCall(spec, adviceBody);
      writeAdviceMethodCall(spec, adviceBody);
    } else {
      type = "AROUND";
      writeAdviceMethodCall(spec, adviceBody);
    }
    body.addStatement(
        new MethodCallExpr()
            .setScope(new NameExpr("container"))
            .setName("addAdvice")
            .setArguments(
                new NodeList<>(
                    new FieldAccessExpr()
                        .setScope(
                            new TypeExpr(new ClassOrInterfaceType().setName(ADVICE_TYPE_CLASS)))
                        .setName(type),
                    new StringLiteralExpr(pointCut.getOwner().getInternalName()),
                    new StringLiteralExpr(pointCut.getMethodName()),
                    new StringLiteralExpr(pointCut.getMethodType().getDescriptor()),
                    advice)));
  }

  private static void addHelpersInvocation(final Type[] helpers, final BlockStmt body) {
    if (helpers != null && helpers.length > 0) {
      final List<Expression> helperTypes =
          Arrays.stream(helpers)
              .map(type -> new StringLiteralExpr(type.getClassName()))
              .collect(Collectors.toList());
      body.addStatement(
          new MethodCallExpr()
              .setScope(new NameExpr("container"))
              .setName("addHelpers")
              .setArguments(new NodeList<>(helperTypes)));
    }
  }

  private static void addEnabledCheck(
      final ClassOrInterfaceDeclaration type, final Enabled enabled) {
    type.addImplementedType(HAS_ENABLED_PROPERTY_CLASS);

    final MethodType method = enabled.getMethod();
    final String ownerPackage = getPackageName(method.getOwner());
    final String ownerClassName = getClassName(method.getOwner(), false);
    final List<Expression> parameters =
        enabled.getArguments().stream().map(StringLiteralExpr::new).collect(Collectors.toList());

    final Expression enabledCheckExpression =
        new MethodCallExpr()
            .setScope(new NameExpr(ownerPackage + "." + ownerClassName))
            .setName(method.getMethodName())
            .setArguments(new NodeList<>(parameters));

    type.addMethod("isEnabled", PUBLIC)
        .setType(boolean.class)
        .addAnnotation(Override.class)
        .setBody(
            new BlockStmt().addStatement(new ReturnStmt().setExpression(enabledCheckExpression)));
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
              .setName("advice")
              .addArgument(new StringLiteralExpr(method.getOwner().getInternalName()))
              .addArgument(new StringLiteralExpr(method.getMethodName()))
              .addArgument(new StringLiteralExpr(method.getMethodType().getDescriptor()));
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

  private static Expression invokeAdviceSignature(final BlockStmt body) {
    return new LambdaExpr(
        new NodeList<>(
            new Parameter().setName("handler").setType(METHOD_HANDLER_CLASS),
            new Parameter().setName("opcode").setType(int.class),
            new Parameter().setName("owner").setType(String.class),
            new Parameter().setName("name").setType(String.class),
            new Parameter().setName("descriptor").setType(String.class),
            new Parameter().setName("isInterface").setType(boolean.class)),
        body);
  }

  private static Expression invokeDynamicAdviceSignature(final BlockStmt body) {
    return new LambdaExpr(
        new NodeList<>(
            new Parameter().setName("handler").setType(METHOD_HANDLER_CLASS),
            new Parameter().setName("name").setType(String.class),
            new Parameter().setName("descriptor").setType(String.class),
            new Parameter().setName("bootstrapMethodHandle").setType(HANDLE_FQDN),
            new Parameter()
                .setVarArgs(true)
                .setType(Object.class)
                .setName("bootstrapMethodArguments")),
        body);
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
