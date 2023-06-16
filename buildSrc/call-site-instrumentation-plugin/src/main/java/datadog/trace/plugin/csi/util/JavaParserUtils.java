package datadog.trace.plugin.csi.util;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.*;

public abstract class JavaParserUtils {

  private JavaParserUtils() {}

  public static Expression intLiteral(final int value) {
    return new IntegerLiteralExpr().setValue(Integer.toString(value));
  }

  public static FieldAccessExpr accessLocalField(final String name) {
    return new FieldAccessExpr().setScope(new ThisExpr()).setName(name);
  }

  public static ClassOrInterfaceDeclaration getPrimaryType(final CompilationUnit javaClass) {
    return javaClass
        .getPrimaryType()
        .orElseGet(() -> javaClass.getTypes().get(0))
        .asClassOrInterfaceDeclaration();
  }

  public static boolean implementsInterface(
      final ClassOrInterfaceDeclaration type, final String interfaceName) {
    return type.getImplementedTypes().stream()
        .anyMatch(it -> it.getNameAsString().equals(interfaceName));
  }
}
