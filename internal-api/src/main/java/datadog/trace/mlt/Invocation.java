package datadog.trace.mlt;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class Invocation {
  static final int INVOCATION_OFFSET = 3;

  /**
   * A simple data class representing an invocation caller. Provides the caller class and method
   * name.
   */
  public static final class Caller {
    public final String className;
    public final String methodName;

    Caller(String className, String methodName) {
      this.className = className;
      this.methodName = methodName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Caller caller = (Caller) o;
      return className.equals(caller.className) && Objects.equals(methodName, caller.methodName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(className, methodName);
    }
  }

  /**
   * Invocation caller detection can use StackWalker API which available in Java 9+ only. Therefore
   * abstracting the common interface which can be implemented by the default (pre Java 9)
   * implementation and the post Java 9 one as well.
   */
  public interface Impl {
    @NonNull
    Caller getCaller(int offset);

    @NonNull
    List<Caller> getCallers();
  }

  private static final Invocation.Impl impl;

  static {
    Invocation.Impl rtImpl = new InvocationImplDefault();
    try {
      Runtime.class.getMethod("version");
      Constructor<?> constructor =
          Class.forName(Invocation.class.getPackage().getName() + ".InvocationImplStackWalker")
              .getDeclaredConstructor();
      constructor.setAccessible(true);
      rtImpl = (Invocation.Impl) constructor.newInstance();
    } catch (NoSuchMethodException ignored) {
      // running on pre-9 Java; silently ignore and use the default impl
    } catch (Throwable t) {
      log.info(
          "Unable to instantiate StackWalker API based invocation caller impl. Using the default one.",
          t);
    }

    impl = rtImpl;
  }

  /**
   * Get the method {@linkplain Caller} adjusted by offset. An offset of 0 will return the immediate
   * caller.
   *
   * @param offset the offset to adjust the caller stackframe by
   * @return the {@linkplain Caller} located {@literal offset} stackframes above the called method;
   *     {@literal null} if the offset is bigger than the current stack depth
   */
  public static Caller getCaller(int offset) {
    return impl.getCaller(offset);
  }

  /**
   * Get the caller chain in the form of a list starting at the immediate caller and ending at the
   * callstack root.
   *
   * @return the caller chain in the form of a list starting at the immediate caller and ending at
   *     the callstack root
   */
  @NonNull
  public static List<Caller> getCallers() {
    return impl.getCallers();
  }
}
