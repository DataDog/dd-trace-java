package datadog.apt;

import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.AbstractAnnotationValueVisitor6;

/**
 * Utility class that works AnnotationMirrors & AnnotationValues
 *
 * <p>By convention, nulls pass through nicely to allow easy composition
 */
public final class AnnoUtils {
  private AnnoUtils() {}

  /** Finds the AnnotationMirror on Element of type TypeElement */
  public static final AnnotationMirror findAnnotation(Element element, TypeElement annoType) {
    if (element == null || annoType == null) return null;

    for (AnnotationMirror annoMirror : element.getAnnotationMirrors()) {
      if (isA(annoMirror, annoType)) return annoMirror;
    }
    return null;
  }

  public static final AnnotationMirror findAnnotation(Element element, Class<?> annoClass) {
    if (element == null || annoClass == null) return null;

    for (AnnotationMirror annoMirror : element.getAnnotationMirrors()) {
      if (isA(annoMirror, annoClass)) return annoMirror;
    }
    return null;
  }

  public static final boolean isSuppressed(Element element, String checkName) {
    if (element == null) return false;
    if (isSuppressedHelper(element, checkName)) return true;

    for (Element enclosingElement = element.getEnclosingElement();
        enclosingElement != null;
        enclosingElement = enclosingElement.getEnclosingElement()) {
      if (isSuppressedHelper(enclosingElement, checkName)) return true;
    }
    return false;
  }

  private static final boolean isSuppressedHelper(Element element, String checkName) {
    AnnotationMirror mirror = findAnnotation(element, SuppressWarnings.class);
    AnnotationValue value = getValue(mirror);
    List<? extends AnnotationValue> suppressionList = asList(value);

    return contains(suppressionList, checkName);
  }

  public static final boolean contains(List<? extends AnnotationValue> annoList, Object value) {
    if (annoList == null) return false;

    for (AnnotationValue annoValue : annoList) {
      if (is(annoValue, value)) return true;
    }
    return false;
  }

  public static final boolean isA(AnnotationMirror annoMirror, TypeElement annoType) {
    if (annoMirror == null) return false;

    return annoMirror.getAnnotationType().asElement().equals(annoType);
  }

  public static final boolean isA(AnnotationMirror annoMirror, Class<?> annoClass) {
    if (annoMirror == null) return false;

    return TypeUtils.isClass(annoMirror.getAnnotationType(), annoClass);
  }

  public static final boolean is(AnnotationValue annoValue, Object obj) {
    // TODO: Add support for Class objects?
    return (annoValue == null) ? false : annoValue.getValue().equals(obj);
  }

  public static final TypeMirror asType(AnnotationValue annoValue) {
    return (annoValue == null) ? null : (TypeMirror) annoValue.getValue();
  }

  public static final List<? extends AnnotationValue> asList(AnnotationValue annoValue) {
    if (annoValue == null) return null;

    return annoValue.accept(
        new AnnotationVisitor<List<? extends AnnotationValue>, Void>() {
          @Override
          public List<? extends AnnotationValue> visitArray(
              List<? extends AnnotationValue> vals, Void p) {
            return vals;
          }
        },
        null);
  }

  public static final AnnotationValue getValue(AnnotationMirror annoMirror) {
    return getValue(annoMirror, "value");
  }

  public static final AnnotationValue getValue(AnnotationMirror annoMirror, String simpleName) {
    if (annoMirror == null) return null;

    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> elementEntry :
        annoMirror.getElementValues().entrySet()) {
      ExecutableElement element = elementEntry.getKey();
      if (!element.getSimpleName().contentEquals(simpleName)) continue;

      return elementEntry.getValue();
    }
    return null;
  }

  abstract static class AnnotationVisitor<R, P> extends AbstractAnnotationValueVisitor6<R, P> {
    @Override
    public R visitArray(List<? extends AnnotationValue> vals, P p) {
      return null;
    }

    @Override
    public R visitBoolean(boolean b, P p) {
      return null;
    }

    @Override
    public R visitByte(byte b, P p) {
      return null;
    }

    @Override
    public R visitChar(char c, P p) {
      return null;
    }

    @Override
    public R visitDouble(double d, P p) {
      return null;
    }

    @Override
    public R visitFloat(float f, P p) {
      return null;
    }

    @Override
    public R visitInt(int i, P p) {
      return null;
    }

    @Override
    public R visitLong(long i, P p) {
      return null;
    }

    @Override
    public R visitShort(short s, P p) {
      return null;
    }

    @Override
    public R visitString(String s, P p) {
      return null;
    }

    @Override
    public R visitType(TypeMirror t, P p) {
      return null;
    }

    @Override
    public R visitEnumConstant(VariableElement c, P p) {
      return null;
    }

    @Override
    public R visitAnnotation(AnnotationMirror a, P p) {
      return null;
    }
  }
}
