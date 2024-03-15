package datadog.trace.util;

// FIXME: nikita: add Javadoc and maybe rename
public abstract class ShadowUtils {

  private static final boolean IS_SHADOWED_TRACER = ShadowUtils.class.getPackage().getName().startsWith("shadow.");

  private ShadowUtils() {}

  public static boolean isShadowedTracer() {
    return IS_SHADOWED_TRACER;
  }

  public static String getShadowedPropertyName(String name) {
    return "shadow." + name;
  }

  public static String shadowPropertyNameIfNeeded(String name) {
    return IS_SHADOWED_TRACER ? getShadowedPropertyName(name) : name;
  }
}
