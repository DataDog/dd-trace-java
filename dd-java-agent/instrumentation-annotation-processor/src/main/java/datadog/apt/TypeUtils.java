package datadog.apt;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.NullType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.AbstractTypeVisitor6;

/**
 * Utility class for working with Types
 *
 * <p>By convention, nulls pass through nicely to allow easy composition
 */
public final class TypeUtils {
  private TypeUtils() {}

  public static final TypeElement findType(
      Iterable<? extends TypeElement> types, String qualifiedName) {
    if (types == null) return null;

    for (TypeElement type : types) {
      if (type.getQualifiedName().contentEquals(qualifiedName)) return type;
    }
    return null;
  }

  public static final boolean isClass(DeclaredType type, Class<?> clazz) {
    if (type == null) return false;

    boolean isSimpleMatch = type.asElement().getSimpleName().contentEquals(clazz.getSimpleName());
    if (!isSimpleMatch) return false;

    Element enclosingElement = type.asElement().getEnclosingElement();
    switch (enclosingElement.getKind()) {
      case PACKAGE:
        return ElementUtils.isPackage(enclosingElement, clazz.getPackage());

      case CLASS:
        return ElementUtils.isClass(enclosingElement, clazz.getEnclosingClass());

      default:
        return false;
    }
  }

  public static final boolean isClass(TypeMirror type, Class<?> clazz) {
    if (type == null) return false;

    return type.accept(TypeEquivalenceVisitor.INSTANCE, clazz);
  }

  private static final class TypeEquivalenceVisitor
      extends AbstractTypeVisitor6<Boolean, Class<?>> {
    public static final TypeEquivalenceVisitor INSTANCE = new TypeEquivalenceVisitor();

    private TypeEquivalenceVisitor() {}

    @Override
    public Boolean visitDeclared(DeclaredType type, Class<?> paramClass) {
      return isClass(type, paramClass);
    }

    @Override
    public Boolean visitPrimitive(PrimitiveType t, Class<?> clazz) {
      switch (t.getKind()) {
        case BOOLEAN:
          return boolean.class.equals(clazz);

        case BYTE:
          return byte.class.equals(clazz);

        case CHAR:
          return char.class.equals(clazz);

        case DOUBLE:
          return double.class.equals(clazz);

        case FLOAT:
          return float.class.equals(clazz);

        case INT:
          return int.class.equals(clazz);

        case LONG:
          return long.class.equals(clazz);

        case SHORT:
          return short.class.equals(clazz);

        default:
          return false;
      }
    }

    @Override
    public Boolean visitArray(ArrayType arrayType, Class<?> clazz) {
      if (arrayType == null) return false;
      if (!clazz.isArray()) return false;

      return isClass(arrayType.getComponentType(), clazz.getComponentType());
    }

    @Override
    public Boolean visitError(ErrorType t, Class<?> p) {
      return false;
    }

    @Override
    public Boolean visitExecutable(ExecutableType t, Class<?> p) {
      return false;
    }

    @Override
    public Boolean visitNull(NullType t, Class<?> p) {
      return false;
    }

    @Override
    public Boolean visitNoType(NoType t, Class<?> paramClass) {
      // void???
      return false;
    }

    @Override
    public Boolean visitTypeVariable(TypeVariable t, Class<?> p) {
      return false;
    }

    @Override
    public Boolean visitWildcard(WildcardType t, Class<?> p) {
      return false;
    }
  }
}
