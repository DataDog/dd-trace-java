package datadog.trace.plugin.csi.util;

import static com.github.javaparser.ast.Modifier.Keyword.PUBLIC;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;

@SuppressWarnings("OptionalGetWithoutIsPresent")
public abstract class JavaParserUtils {

  private JavaParserUtils() {}

  public static MethodDeclaration stringLiteralMethod(
      final String name, final String value, final boolean isOverriden) {
    return stringLiteralMethod(name, value, isOverriden, PUBLIC);
  }

  public static MethodDeclaration stringLiteralMethod(
      final String name,
      final String value,
      final boolean isOverriden,
      final Modifier.Keyword... modifiers) {
    return singleStatementMethod(
        name, "String", new StringLiteralExpr(value), isOverriden, modifiers);
  }

  public static MethodDeclaration singleStatementMethod(
      final String name,
      final String returnType,
      final Expression expression,
      final boolean isOverriden) {
    return singleStatementMethod(name, returnType, expression, isOverriden, PUBLIC);
  }

  public static MethodDeclaration singleStatementMethod(
      final String name,
      final String returnType,
      final Expression expression,
      final boolean isOverriden,
      final Modifier.Keyword... modifiers) {
    final MethodDeclaration method = new MethodDeclaration().setName(name).setModifiers(modifiers);
    if (isOverriden) {
      method.addAnnotation(Override.class);
    }
    method.setType(returnType);
    final BlockStmt statements = new BlockStmt();
    statements.addStatement(new ReturnStmt(expression));
    method.setBody(statements);
    return method;
  }

  public static Expression intLiteral(final int value) {
    return new IntegerLiteralExpr().setValue(Integer.toString(value));
  }

  public static void replaceInParent(final Expression source, final Expression target) {
    source.getParentNode().get().replace(source, target);
  }

  public static FieldAccessExpr accessLocalField(final String name) {
    return new FieldAccessExpr().setScope(new ThisExpr()).setName(name);
  }

  public static AssignExpr updateLocalField(final String name, final Expression value) {
    return new AssignExpr()
        .setOperator(AssignExpr.Operator.ASSIGN)
        .setTarget(accessLocalField(name))
        .setValue(value);
  }

  public static TypeDeclaration<?> getPrimaryType(final CompilationUnit javaClass) {
    return javaClass.getPrimaryType().orElseGet(() -> javaClass.getTypes().get(0));
  }
}
