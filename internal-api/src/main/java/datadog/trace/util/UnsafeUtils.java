package datadog.trace.util;

import static net.bytebuddy.matcher.ElementMatchers.isFinal;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.ModifierAdjustment;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.dynamic.DynamicType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

public abstract class UnsafeUtils {

  private static final Logger log = LoggerFactory.getLogger(UnsafeUtils.class);

  private static final Unsafe UNSAFE = getUnsafe();

  private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

  private static Unsafe getUnsafe() {
    try {
      Field f = Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      return (Unsafe) f.get(null);

    } catch (Throwable t) {
      log.debug("Unsafe is unavailable", t);
      return null;
    }
  }

  /**
   * Tries to create a shallow clone of the provided object: another instance of the same class
   * whose fields have the same values as the original (for reference fields that means referring to
   * the same objects).
   *
   * <p>If cloning fails, the original instance is returned.
   *
   * @param original Original object
   * @return A shallow clone
   * @param <T> Type of the object being cloned
   */
  @SuppressWarnings("unchecked")
  public static <T> T originalTryShallowClone(T original) {
    if (UNSAFE == null) {
      log.debug("Unsafe is unavailable, {} will not be cloned", original);
      return original;
    }
    try {
      Class<?> clazz = original.getClass();
      T clone = (T) UNSAFE.allocateInstance(clazz);

      while (clazz != Object.class) {
        cloneFields(clazz, original, clone);
        clazz = clazz.getSuperclass();
      }
      return clone;

    } catch (Throwable t) {
      log.debug("Error while cloning {}: {}", original, t);
      return original;
    }
  }

  public static <T> T tryShallowClone(T original) {
    if (UNSAFE == null) {
      log.debug("Unsafe is unavailable, {} will not be cloned", original);
      return original;
    }
    try {
      Class<?> clazz = original.getClass();
      if (!CLASS_CACHE.containsKey(clazz.getName())) {
        CLASS_CACHE.put(clazz.getName(), createNonFinalSubclass(clazz));
      }
      Class<?> nonFinalSubclass = CLASS_CACHE.get(clazz.getName());

      T clone = (T) UNSAFE.allocateInstance(nonFinalSubclass);

      while (clazz != Object.class) {
        cloneFields(clazz, original, clone);
        clazz = clazz.getSuperclass();
      }
      return clone;

    } catch (Throwable t) {
      log.debug("Error while cloning {}: {}", original, t);
      t.printStackTrace();
      return original;
    }
  }

  private static Class<?> createNonFinalSubclass(Class<?> clazz) throws Exception {
    DynamicType.Unloaded<?> dynamicType =
        new ByteBuddy()
            .subclass(clazz)
            .visit(new ModifierAdjustment().withFieldModifiers(isFinal(), FieldManifestation.PLAIN))
            .make();
    return dynamicType.load(clazz.getClassLoader()).getLoaded();
  }

  // Field::set() is forbidden because it may be used to mutate final fields, disallowed by
  // https://openjdk.org/jeps/500.
  // However, in this case we skip final fields, so it is safe.
  @SuppressForbidden
  private static void cloneFields(Class<?> clazz, Object original, Object clone) throws Exception {
    for (Field field : clazz.getDeclaredFields()) {
      if ((field.getModifiers() & Modifier.STATIC) != 0) {
        continue;
      }
      field.setAccessible(true);
      Object fieldValue = field.get(original);
      field.set(clone, fieldValue);
    }
  }
}
