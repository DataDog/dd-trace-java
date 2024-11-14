package datadog.apt;

import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

/**
 * Utility class for working with Elements
 *
 * <p>By convention, nulls pass through nicely to allow easy composition
 */
public final class ElementUtils {
  private ElementUtils() {}

  public static final boolean isPackage(Element element, Package pkg) {
    if (element == null) return false;

    return isPackage(element, pkg.getName());
  }

  public static final boolean isPackage(Element element, String packageName) {
    if (!(element instanceof PackageElement)) return false;

    PackageElement packageElement = (PackageElement) element;

    return packageElement.getQualifiedName().contentEquals(packageName);
  }

  public static final boolean isClass(Element element, Class<?> clazz) {
    if (!(element instanceof TypeElement)) return false;
    TypeElement typeElement = (TypeElement) element;

    return TypeUtils.isClass(typeElement.asType(), clazz);
  }
}
