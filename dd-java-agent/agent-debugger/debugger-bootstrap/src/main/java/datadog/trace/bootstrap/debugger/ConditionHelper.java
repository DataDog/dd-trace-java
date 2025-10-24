package datadog.trace.bootstrap.debugger;

import datadog.trace.bootstrap.debugger.el.ReflectiveFieldValueResolver;
import datadog.trace.bootstrap.debugger.util.WellKnownClasses;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.DoublePredicate;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

public class ConditionHelper {

  // comes from ASM
  private static final int IFEQ = 153;
  private static final int IFLT = 155;
  private static final int IFGE = 156;
  private static final int IFGT = 157;
  private static final int IFLE = 158;

  public static boolean equalsForEnum(Enum<?> enumInstance, String enumValueStr) {
    Class<? extends Enum> enumClass = enumInstance.getClass();
    Enum[] enumConstants = enumClass.getEnumConstants();
    for (Enum<?> enumConstant : enumConstants) {
      // Check if string constant as value expression matches for enum constant
      // the endsWith allow to match either:
      // - the full enum constant name (com.datadog.debugger.MyEnum.ONE)
      // - the simple name with enum class name (MyEnum.ONE)
      // - the simple name (ONE)
      // The second check against enumValue is to ensure the instance filtered based on the
      // name is still correct because the name can partially match (CLOSE in OPENCLOSE)
      // with an enum defined like (OPEN, CLOSE, OPENCLOSE)
      if (enumValueStr.endsWith(enumConstant.name())) {
        if (enumInstance.equals(enumConstant)) {
          return true;
        }
      }
    }
    return false;
  }

  public static boolean equalsWithInstanceOf(Object left, String className) {
    Class<?> clazz;
    try {
      clazz = Class.forName(className, false, left.getClass().getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Class not found: " + className);
    }
    return clazz.isInstance(left);
  }

  public static boolean compareTo(Object left, Object right, int cmpOpcode) {
    if (!(left instanceof Number) || !(right instanceof Number)) {
      throw new IllegalArgumentException(
          "Incompatible types for compareTo: " + left + " and " + right);
    }
    Number leftNumber = (Number) left;
    Number rightNumber = (Number) right;
    if (isNan(leftNumber, rightNumber)) {
      return false;
    }
    switch (cmpOpcode) {
      case IFEQ:
        return compare(leftNumber, rightNumber) == 0;
      case IFGE:
        return compare(leftNumber, rightNumber) >= 0;
      case IFGT:
        return compare(leftNumber, rightNumber) > 0;
      case IFLE:
        return compare(leftNumber, rightNumber) <= 0;
      case IFLT:
        return compare(leftNumber, rightNumber) < 0;
      default:
        throw new IllegalArgumentException("Invalid cmp opcode: " + cmpOpcode);
    }
  }

  public static Object resolveByFieldName(Object target, String memberName) {
    try {
      return ReflectiveFieldValueResolver.getFieldValue(target, memberName);
    } catch (NoSuchFieldException | IllegalAccessException ex) {
      throw new RuntimeException(ex);
    }
  }

  public static boolean isNan(Number... numbers) {
    boolean result = false;
    for (Number number : numbers) {
      result |= number instanceof Double && Double.isNaN(number.doubleValue());
    }
    return result;
  }

  public static int compare(Number left, Number right) {
    if (isSpecial(left) || isSpecial(right)) {
      return Double.compare(left.doubleValue(), right.doubleValue());
    } else {
      return toBigDecimal(left).compareTo(toBigDecimal(right));
    }
  }

  public static boolean contains(Object source, Object value) {
    if (source == null) {
      return false;
    }
    if (source instanceof Collection) {
      if (WellKnownClasses.isSafe((Collection<?>) source)
          && (value == null || WellKnownClasses.isEqualsSafe(value.getClass()))) {
        return ((Collection<?>) source).contains(value);
      }
      return false;
    }
    if (source instanceof Map) {
      if (WellKnownClasses.isSafe((Map<?, ?>) source)
          && (value == null || WellKnownClasses.isEqualsSafe(value.getClass()))) {
        return ((Map<?, ?>) source).containsKey(value);
      }
      return false;
    }
    if (source.getClass().isArray()) {
      Class<?> componentType = source.getClass().getComponentType();
      int count = Array.getLength(source);
      if (componentType.isPrimitive()) {
        if (componentType == byte.class) {
          byte byteValue = (Byte) value;
          for (int i = 0; i < count; i++) {
            if (Array.getByte(source, i) == byteValue) {
              return true;
            }
          }
        } else if (componentType == char.class) {
          String strValue = (String) value;
          if (strValue.isEmpty()) {
            return false;
          }
          char charValue = strValue.charAt(0);
          for (int i = 0; i < count; i++) {
            if (Array.getChar(source, i) == charValue) {
              return true;
            }
          }
        } else if (componentType == short.class) {
          int shortValue = ((Number) value).intValue();
          for (int i = 0; i < count; i++) {
            if (Array.getShort(source, i) == shortValue) {
              return true;
            }
          }
        } else if (componentType == int.class) {
          int intValue = ((Number) value).intValue();
          for (int i = 0; i < count; i++) {
            if (Array.getInt(source, i) == intValue) {
              return true;
            }
          }
        } else if (componentType == long.class) {
          long longValue = ((Number) value).longValue();
          for (int i = 0; i < count; i++) {
            if (Array.getLong(source, i) == longValue) {
              return true;
            }
          }
        } else if (componentType == float.class) {
          float floatValue = (Float) value;
          for (int i = 0; i < count; i++) {
            if (Array.getFloat(source, i) == floatValue) {
              return true;
            }
          }
        } else if (componentType == double.class) {
          double doubleValue = (Double) value;
          for (int i = 0; i < count; i++) {
            if (Array.getDouble(source, i) == doubleValue) {
              return true;
            }
          }
        } else if (componentType == boolean.class) {
          boolean booleanValue = (Boolean) value;
          for (int i = 0; i < count; i++) {
            if (Array.getBoolean(source, i) == booleanValue) {
              return true;
            }
          }
        }
      } else {
        if (WellKnownClasses.isEqualsSafe(value.getClass())) {
          for (int i = 0; i < count; i++) {
            if (value.equals(Array.get(source, i))) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  public static boolean isEmpty(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof String) {
      return ((String) value).isEmpty();
    }
    if (value instanceof Collection && WellKnownClasses.isSafe((Collection<?>) value)) {
      return ((Collection<?>) value).isEmpty();
    }
    if (value instanceof Map && WellKnownClasses.isSafe((Map<?, ?>) value)) {
      return ((Map<?, ?>) value).isEmpty();
    }
    if (value.getClass().isArray()) {
      return Array.getLength(value) == 0;
    }
    return false;
  }

  public static int len(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof String) {
      return ((String) value).length();
    }
    if (value instanceof Collection && WellKnownClasses.isSafe((Collection<?>) value)) {
      return ((Collection<?>) value).size();
    }
    if (value instanceof Map && WellKnownClasses.isSafe((Map<?, ?>) value)) {
      return ((Map<?, ?>) value).size();
    }
    if (value.getClass().isArray()) {
      return Array.getLength(value);
    }
    throw new IllegalArgumentException("Unsupported class for len operation: " + value.getClass());
  }

  public static boolean stringPredicate(
      Object value, String strExpr, BiPredicate<String, String> strPredicateFunc) {
    if (value == null) {
      return false;
    }
    if (value instanceof String) {
      return strPredicateFunc.test((String) value, strExpr);
    }
    // TODO throw new IllegalArgumentException();
    return false;
  }

  public static String substring(Object value, int beginIndex, int endIndex) {
    if (value == null) {
      return null;
    }
    if (value instanceof String) {
      return ((String) value).substring(beginIndex, endIndex);
    }
    return null;
  }

  public static Object index(Object value, int index) {
    if (value == null) {
      return null;
    }
    if (value instanceof List && WellKnownClasses.isSafe((List) value)) {
      return ((List) value).get(index);
    }
    if (value instanceof Map && WellKnownClasses.isSafe((Map<?, ?>) value)) {
      return ((Map<?, ?>) value).get(index);
    }
    throw new UnsupportedOperationException(
        "Unsupported type for index operation: " + value.getClass());
  }

  public static Object index(Object value, Object key) {
    if (value instanceof List && WellKnownClasses.isSafe((List) value) && key instanceof Number) {
      return ((List) value).get(((Number) key).intValue());
    }
    if (value instanceof Map && WellKnownClasses.isSafe((Map<?, ?>) value)) {
      return ((Map<?, ?>) value).get(key);
    }
    throw new UnsupportedOperationException(
        "Unsupported type for index operation: " + value.getClass());
  }

  public interface BooleanPredicate {
    boolean test(boolean value);
  }

  public static boolean[] filterBooleanArray(boolean[] array, BooleanPredicate predicate) {
    boolean[] result = new boolean[array.length];
    int resultIndex = 0;
    for (int i = 0; i < array.length; i++) {
      boolean value = array[i];
      if (predicate.test(value)) {
        result[resultIndex++] = value;
      }
    }
    return Arrays.copyOfRange(result, 0, resultIndex);
  }

  public static byte[] filterByteArray(byte[] array, IntPredicate predicate) {
    byte[] result = new byte[array.length];
    int resultIndex = 0;
    for (int i = 0; i < array.length; i++) {
      byte value = array[i];
      if (predicate.test(value)) {
        result[resultIndex++] = value;
      }
    }
    return Arrays.copyOfRange(result, 0, resultIndex);
  }

  public static short[] filterShortArray(short[] array, IntPredicate predicate) {
    short[] result = new short[array.length];
    int resultIndex = 0;
    for (int i = 0; i < array.length; i++) {
      short value = array[i];
      if (predicate.test(value)) {
        result[resultIndex++] = value;
      }
    }
    return Arrays.copyOfRange(result, 0, resultIndex);
  }

  public static char[] filterCharArray(char[] array, IntPredicate predicate) {
    char[] result = new char[array.length];
    int resultIndex = 0;
    for (int i = 0; i < array.length; i++) {
      char value = array[i];
      if (predicate.test(value)) {
        result[resultIndex++] = value;
      }
    }
    return Arrays.copyOfRange(result, 0, resultIndex);
  }

  public static int[] filterIntArray(int[] array, IntPredicate predicate) {
    int[] result = new int[array.length];
    int resultIndex = 0;
    for (int i = 0; i < array.length; i++) {
      int value = array[i];
      if (predicate.test(value)) {
        result[resultIndex++] = value;
      }
    }
    return Arrays.copyOfRange(result, 0, resultIndex);
  }

  public static long[] filterLongArray(long[] array, LongPredicate predicate) {
    long[] result = new long[array.length];
    int resultIndex = 0;
    for (int i = 0; i < array.length; i++) {
      long value = array[i];
      if (predicate.test(value)) {
        result[resultIndex++] = value;
      }
    }
    return Arrays.copyOfRange(result, 0, resultIndex);
  }

  public static float[] filterFloatArray(float[] array, DoublePredicate predicate) {
    float[] result = new float[array.length];
    int resultIndex = 0;
    for (int i = 0; i < array.length; i++) {
      float value = array[i];
      if (predicate.test(value)) {
        result[resultIndex++] = value;
      }
    }
    return Arrays.copyOfRange(result, 0, resultIndex);
  }

  public static double[] filterDoubleArray(double[] array, DoublePredicate predicate) {
    double[] result = new double[array.length];
    int resultIndex = 0;
    for (int i = 0; i < array.length; i++) {
      double value = array[i];
      if (predicate.test(value)) {
        result[resultIndex++] = value;
      }
    }
    return Arrays.copyOfRange(result, 0, resultIndex);
  }

  public static Object[] filterObjectArray(Object[] array, Predicate<? super Object> predicate) {
    Object[] result = new Object[array.length];
    int resultIndex = 0;
    for (int i = 0; i < array.length; i++) {
      Object value = array[i];
      if (predicate.test(value)) {
        result[resultIndex++] = value;
      }
    }
    return Arrays.copyOfRange(result, 0, resultIndex);
  }

  public static <T> Collection<T> filterCollection(
      Collection<T> collection, Predicate<T> predicate) {
    Collection<T> result =
        new ArrayList<>(); // TODO create a similar collection type or specialized helper?
    for (T value : collection) {
      if (predicate.test(value)) {
        result.add(value);
      }
    }
    return result;
  }

  public static <K, V, T> Map<K, V> filterMap(Map<K, V> map, Predicate<T> predicate) {
    // TODO
    return null;
  }

  public static boolean anyLongArray(long[] array, LongPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      long value = array[i];
      if (predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  public static boolean anyBooleanArray(boolean[] array, BooleanPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      boolean value = array[i];
      if (predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  public static boolean anyByteArray(byte[] array, IntPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      byte value = array[i];
      if (predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  public static boolean anyShortArray(short[] array, IntPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      short value = array[i];
      if (predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  public static boolean anyCharArray(char[] array, IntPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      char value = array[i];
      if (predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  public static boolean anyIntArray(int[] array, IntPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      int value = array[i];
      if (predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  public static boolean anyFloatArray(float[] array, DoublePredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      float value = array[i];
      if (predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  public static boolean anyDoubleArray(float[] array, DoublePredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      double value = array[i];
      if (predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  public static boolean anyObjectArray(Object[] array, Predicate<? super Object> predicate) {
    for (int i = 0; i < array.length; i++) {
      Object value = array[i];
      if (predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  public static <T> boolean anyCollection(Collection<T> collection, Predicate<T> predicate) {
    for (T value : collection) {
      if (predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  public static boolean allBooleanArray(boolean[] array, BooleanPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      boolean value = array[i];
      if (!predicate.test(value)) {
        return false;
      }
    }
    return true;
  }

  public static boolean allByteArray(byte[] array, IntPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      int value = array[i];
      if (!predicate.test(value)) {
        return false;
      }
    }
    return true;
  }

  public static boolean allShortArray(short[] array, IntPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      short value = array[i];
      if (!predicate.test(value)) {
        return false;
      }
    }
    return true;
  }

  public static boolean allCharArray(char[] array, IntPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      char value = array[i];
      if (!predicate.test(value)) {
        return false;
      }
    }
    return true;
  }

  public static boolean allIntArray(int[] array, IntPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      int value = array[i];
      if (!predicate.test(value)) {
        return false;
      }
    }
    return true;
  }

  public static boolean allLongArray(long[] array, LongPredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      long value = array[i];
      if (!predicate.test(value)) {
        return false;
      }
    }
    return true;
  }

  public static boolean allFloatArray(float[] array, DoublePredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      float value = array[i];
      if (!predicate.test(value)) {
        return false;
      }
    }
    return true;
  }

  public static boolean allDoubleArray(double[] array, DoublePredicate predicate) {
    for (int i = 0; i < array.length; i++) {
      double value = array[i];
      if (!predicate.test(value)) {
        return false;
      }
    }
    return true;
  }

  public static boolean allObjectArray(Object[] array, Predicate<? super Object> predicate) {
    for (int i = 0; i < array.length; i++) {
      Object value = array[i];
      if (!predicate.test(value)) {
        return false;
      }
    }
    return true;
  }

  public static <T> boolean allCollection(Collection<T> collection, Predicate<T> predicate) {
    for (T value : collection) {
      if (!predicate.test(value)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isSpecial(Number x) {
    boolean specialDouble = x instanceof Double && Double.isInfinite((Double) x);
    boolean specialFloat = x instanceof Float && Float.isInfinite((Float) x);
    return specialDouble || specialFloat;
  }

  private static BigDecimal toBigDecimal(Number number) throws NumberFormatException {
    if (number instanceof BigDecimal) return (BigDecimal) number;
    if (number instanceof BigInteger) return new BigDecimal((BigInteger) number);
    if (number instanceof Byte
        || number instanceof Short
        || number instanceof Integer
        || number instanceof Long) return BigDecimal.valueOf(number.longValue());
    if (number instanceof Float || number instanceof Double)
      return BigDecimal.valueOf(number.doubleValue());

    return new BigDecimal(number.toString());
  }
}
