package datadog.trace.bootstrap.debugger.el;

/**
 * A helper class to create properly formatted reference paths.
 *
 * @see ValueReferenceResolver
 */
public final class ValueReferences {

  public static final String SYNTHETIC_PREFIX = "@";
  public static final String THIS = "this";

  public static final String DURATION_EXTENSION_NAME = "duration";
  public static final String RETURN_EXTENSION_NAME = "return";
  public static final String ITERATOR_EXTENSION_NAME = "it";
  public static final String EXCEPTION_EXTENSION_NAME = "exception";
  public static final String KEY_EXTENSION_NAME = "key";
  public static final String VALUE_EXTENSION_NAME = "value";
  public static final String DURATION_REF = SYNTHETIC_PREFIX + DURATION_EXTENSION_NAME;
  public static final String RETURN_REF = SYNTHETIC_PREFIX + RETURN_EXTENSION_NAME;
  public static final String ITERATOR_REF = SYNTHETIC_PREFIX + ITERATOR_EXTENSION_NAME;
  public static final String EXCEPTION_REF = SYNTHETIC_PREFIX + EXCEPTION_EXTENSION_NAME;
  public static final String KEY_REF = SYNTHETIC_PREFIX + KEY_EXTENSION_NAME;
  public static final String VALUE_REF = SYNTHETIC_PREFIX + VALUE_EXTENSION_NAME;

  public static String synthetic(String name) {
    return SYNTHETIC_PREFIX + name;
  }
}
