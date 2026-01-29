package datadog.trace.plugin.csi.impl.assertion;

import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver;
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import datadog.trace.agent.tooling.csi.CallSites;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class AssertBuilder {
  protected final File file;

  public AssertBuilder(File file) {
    this.file = file;
  }

  public CallSiteAssert build() {
    CompilationUnit javaFile;
    try {
      javaFile = parseJavaFile(file);
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }
    if (javaFile.getParsed() != Node.Parsedness.PARSED) {
      throw new IllegalStateException("Failed to parse file: " + file);
    }
    ClassOrInterfaceDeclaration targetType =
        javaFile.getPrimaryType().get().asClassOrInterfaceDeclaration();
    Set<Class<?>> interfaces = getInterfaces(targetType);
    Method enabled = null;
    Set<String> enabledArgs = null;
    Object[] enabledDeclaration = getEnabledDeclaration(targetType, interfaces);
    if (enabledDeclaration != null) {
      enabled = (Method) enabledDeclaration[0];
      enabledArgs = (Set<String>) enabledDeclaration[1];
    }
    return new CallSiteAssert(
        interfaces,
        getSpi(targetType),
        getHelpers(targetType),
        getAdvices(targetType),
        enabled,
        enabledArgs);
  }

  protected Set<Class<?>> getSpi(ClassOrInterfaceDeclaration type) {
    return type.getAnnotationByName("AutoService").stream()
        .flatMap(
            annotation ->
                annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("value"))
                    .flatMap(
                        pair ->
                            pair.getValue().asArrayInitializerExpr().getValues().stream()
                                .map(
                                    value ->
                                        value
                                            .asClassExpr()
                                            .getType()
                                            .resolve()
                                            .asReferenceType()
                                            .getTypeDeclaration()
                                            .get()
                                            .getQualifiedName())))
        .map(AssertBuilder::loadClass)
        .collect(Collectors.toSet());
  }

  protected Set<Class<?>> getInterfaces(ClassOrInterfaceDeclaration type) {
    return type.getImplementedTypes().stream()
        .map(
            implementedType -> {
              String qualifiedName =
                  implementedType
                      .asClassOrInterfaceType()
                      .resolve()
                      .asReferenceType()
                      .getTypeDeclaration()
                      .get()
                      .getQualifiedName();
              return loadClass(qualifiedName);
            })
        .collect(Collectors.toSet());
  }

  private static Class<?> loadClass(String qualifiedName) {
    // Try the name as-is first
    try {
      return Class.forName(qualifiedName);
    } catch (ClassNotFoundException e) {
      // Try progressively replacing dots with $ from right to left for inner classes
      // We need to try all possible combinations
      String current = qualifiedName;
      int lastDot = current.lastIndexOf('.');
      while (lastDot > 0) {
        current = current.substring(0, lastDot) + "$" + current.substring(lastDot + 1);
        try {
          return Class.forName(current);
        } catch (ClassNotFoundException ex) {
          // Continue trying with the next dot
          lastDot = current.lastIndexOf('.', lastDot - 1);
        }
      }
      throw new RuntimeException(new ClassNotFoundException(qualifiedName));
    }
  }

  protected Object[] getEnabledDeclaration(
      ClassOrInterfaceDeclaration type, Set<Class<?>> interfaces) {
    if (!interfaces.contains(CallSites.HasEnabledProperty.class)) {
      return null;
    }
    MethodDeclaration isEnabled = type.getMethodsByName("isEnabled").get(0);
    MethodCallExpr enabledMethodCall =
        isEnabled
            .getBody()
            .get()
            .getStatements()
            .get(0)
            .asReturnStmt()
            .getExpression()
            .get()
            .asMethodCallExpr();
    Method enabled = resolveMethod(enabledMethodCall);
    Set<String> enabledArgs =
        enabledMethodCall.getArguments().stream()
            .map(arg -> arg.asStringLiteralExpr().asString())
            .collect(Collectors.toSet());
    return new Object[] {enabled, enabledArgs};
  }

  protected Set<Class<?>> getHelpers(ClassOrInterfaceDeclaration type) {
    MethodDeclaration acceptMethod = type.getMethodsByName("accept").get(0);
    List<MethodCallExpr> methodCalls = getMethodCalls(acceptMethod);
    return methodCalls.stream()
        .filter(methodCall -> methodCall.getNameAsString().equals("addHelpers"))
        .flatMap(methodCall -> methodCall.getArguments().stream())
        .map(
            arg -> {
              String className = arg.asStringLiteralExpr().asString();
              return typeResolver().resolveType(classNameToType(className));
            })
        .collect(Collectors.toSet());
  }

  protected List<AdviceAssert> getAdvices(ClassOrInterfaceDeclaration type) {
    MethodDeclaration acceptMethod = type.getMethodsByName("accept").get(0);
    return getMethodCalls(acceptMethod).stream()
        .filter(methodCall -> methodCall.getNameAsString().equals("addAdvice"))
        .map(
            methodCall -> {
              String adviceType = methodCall.getArgument(0).asFieldAccessExpr().getNameAsString();
              String owner = methodCall.getArgument(1).asStringLiteralExpr().asString();
              String method = methodCall.getArgument(2).asStringLiteralExpr().asString();
              String descriptor = methodCall.getArgument(3).asStringLiteralExpr().asString();
              List<String> statements =
                  methodCall
                      .getArgument(4)
                      .asLambdaExpr()
                      .getBody()
                      .asBlockStmt()
                      .getStatements()
                      .stream()
                      .map(Object::toString)
                      .collect(Collectors.toList());
              return new AdviceAssert(adviceType, owner, method, descriptor, statements);
            })
        .collect(Collectors.toList());
  }

  protected static List<MethodCallExpr> getMethodCalls(MethodDeclaration method) {
    return method.getBody().get().asBlockStmt().getStatements().stream()
        .filter(
            stmt ->
                stmt.isExpressionStmt()
                    && stmt.asExpressionStmt().getExpression().isMethodCallExpr())
        .map(stmt -> stmt.asExpressionStmt().getExpression().asMethodCallExpr())
        .collect(Collectors.toList());
  }

  private static Method resolveMethod(MethodCallExpr methodCallExpr) {
    ResolvedMethodDeclaration resolved = methodCallExpr.resolve();
    try {
      Field methodField = resolved.getClass().getDeclaredField("method");
      methodField.setAccessible(true);
      return (Method) methodField.get(resolved);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new RuntimeException(e);
    }
  }

  private static CompilationUnit parseJavaFile(File file) throws FileNotFoundException {
    JavaSymbolSolver solver = new JavaSymbolSolver(typeResolver());
    JavaParser parser = new JavaParser(new ParserConfiguration().setSymbolResolver(solver));
    return parser.parse(file).getResult().get();
  }
}
