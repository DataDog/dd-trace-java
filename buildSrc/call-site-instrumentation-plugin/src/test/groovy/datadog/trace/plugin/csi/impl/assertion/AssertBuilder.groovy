package datadog.trace.plugin.csi.impl.assertion

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import datadog.trace.agent.tooling.csi.CallSites

import java.lang.reflect.Executable
import java.lang.reflect.Method

import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType

class AssertBuilder<C extends CallSiteAssert> {
  private final File file

  AssertBuilder(final File file) {
    this.file = file
  }

  C build() {
    final javaFile = parseJavaFile(file)
    assert javaFile.parsed == Node.Parsedness.PARSED
    final targetType = javaFile.primaryType.get().asClassOrInterfaceDeclaration()
    final interfaces = getInterfaces(targetType)
    def (enabled, enabledArgs) = getEnabledDeclaration(targetType, interfaces)
    return (C) new CallSiteAssert([
      interfaces : getInterfaces(targetType),
      helpers    : getHelpers(targetType),
      advices    : getAdvices(targetType),
      enabled    : enabled,
      enabledArgs: enabledArgs
    ])
  }

  protected List<Class<?>> getInterfaces(final ClassOrInterfaceDeclaration type) {
    return type.asClassOrInterfaceDeclaration().implementedTypes.collect {
      final resolved = it.asClassOrInterfaceType().resolve()
      return resolved.typeDeclaration.get().clazz
    }
  }

  protected def getEnabledDeclaration(final ClassOrInterfaceDeclaration type, final List<Class<?>> interfaces) {
    if (!interfaces.contains(CallSites.HasEnabledProperty)) {
      return [null, null]
    }
    final isEnabled = type.getMethodsByName('isEnabled').first()
    final returnStatement = isEnabled.body.get().statements.first.get().asReturnStmt()
    final enabledMethodCall = returnStatement.expression.get().asMethodCallExpr()
    final enabled = resolveMethod(enabledMethodCall)
    final enabledArgs = enabledMethodCall.getArguments().collect { it.asStringLiteralExpr().asString() }
    return [enabled, enabledArgs]
  }

  protected List<Class<?>> getHelpers(final ClassOrInterfaceDeclaration type) {
    final acceptMethod = type.getMethodsByName('accept').first()
    final methodCalls = getMethodCalls(acceptMethod)
    return methodCalls.findAll {
      it.nameAsString == 'addHelpers'
    }.collectMany {
      it.arguments
    }.collect {
      typeResolver().resolveType(classNameToType(it.asStringLiteralExpr().asString()))
    }
  }

  protected List<AdviceAssert> getAdvices(final ClassOrInterfaceDeclaration type) {
    final acceptMethod = type.getMethodsByName('accept').first()
    return getMethodCalls(acceptMethod).findAll {
      it.nameAsString == 'addAdvice'
    }.collect {
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
  }

  protected static List<MethodCallExpr> getMethodCalls(final MethodDeclaration method) {
    return method.body.get().asBlockStmt().getStatements().findAll {
      it.isExpressionStmt() && it.asExpressionStmt().getExpression().isMethodCallExpr()
    }.collect {
      it.asExpressionStmt().getExpression().asMethodCallExpr()
    }
  }

  private static Executable resolveMethod(final MethodCallExpr methodCallExpr) {
    final resolved = methodCallExpr.resolve()
    return resolved.@method as Method
  }

  private static CompilationUnit parseJavaFile(final File file)
    throws FileNotFoundException {
    final JavaSymbolSolver solver = new JavaSymbolSolver(typeResolver());
    final JavaParser parser = new JavaParser(new ParserConfiguration().setSymbolResolver(solver));
    return parser.parse(file).getResult().get();
  }
}
