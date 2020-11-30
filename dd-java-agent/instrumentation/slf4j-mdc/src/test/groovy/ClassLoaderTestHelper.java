import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.slf4j.MDC;

public final class ClassLoaderTestHelper {
  private final ClassLoader loader = new Loader();

  private final LogContextHelper helper;

  {
    try {
      helper =
          (LogContextHelper)
              loader.loadClass("ClassLoaderTestHelper$LogContextHelperImpl").newInstance();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public void put(String key, Object value) {
    helper.put(key, value);
  }

  public Object get(String key) {
    return helper.get(key);
  }

  public void remove(String key) {
    helper.remove(key);
  }

  public void clear() {
    helper.clear();
  }

  public Map<String, Object> getMap() {
    return helper.getMap();
  }

  private static final class Loader extends ClassLoader {
    public Loader() {
      super(Loader.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.equals("ClassLoaderTestHelper$LogContextHelperImpl")
          || name.startsWith("org.slf4j.")
          || name.startsWith("ch.qos.logback.")) {
        synchronized (getClassLoadingLock(name)) {
          Class<?> klass = findLoadedClass(name);
          if (klass != null) {
            return klass;
          }
          try {
            InputStream inputStream = getResourceAsStream(name.replace('.', '/') + ".class");
            if (inputStream == null) {
              throw new ClassNotFoundException(name);
            }
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            int nextValue;
            while ((nextValue = inputStream.read()) != -1) {
              byteStream.write(nextValue);
            }
            byte[] bytes = byteStream.toByteArray();
            klass = defineClass(name, bytes, 0, bytes.length);
            if (klass == null) {
              throw new ClassNotFoundException(name);
            }
            if (resolve) {
              resolveClass(klass);
            }
            return klass;
          } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
          }
        }
      } else {
        Class<?> klass = super.loadClass(name, resolve);
        return klass;
      }
    }
  }

  // An extra level of indirection to ensure that the MDC is loaded in a new class loader
  public abstract static class LogContextHelper {
    public abstract void put(String key, Object value);

    public abstract Object get(String key);

    public abstract void remove(String key);

    public abstract void clear();

    public abstract Map<String, Object> getMap();
  }

  public static class LogContextHelperImpl extends LogContextHelper {
    @Override
    public void put(String key, Object value) {
      MDC.put(key, value.toString());
    }

    @Override
    public Object get(String key) {
      return MDC.get(key);
    }

    @Override
    public void remove(String key) {
      MDC.remove(key);
    }

    @Override
    public void clear() {
      MDC.clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap() {
      return (Map<String, Object>) ((Map<?, ?>) MDC.getCopyOfContextMap());
    }
  }
}
