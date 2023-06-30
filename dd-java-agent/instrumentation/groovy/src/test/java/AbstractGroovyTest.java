import static org.mockito.ArgumentMatchers.argThat;

import com.datadog.iast.test.IastJavaAgentTestRunner;
import java.lang.reflect.Method;
import org.mockito.ArgumentMatcher;

public abstract class AbstractGroovyTest extends IastJavaAgentTestRunner {

  protected final Object testSuite;
  protected final Method invokeMethod;

  AbstractGroovyTest() {
    testSuite = newSuite();
    invokeMethod = fetchInvokeMethod();
  }

  abstract String suiteName();

  private Object newSuite() {
    try {
      final Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(suiteName());
      return clazz.newInstance();
    } catch (Exception e) {
      return null;
    }
  }

  private Method fetchInvokeMethod() {
    try {
      return testSuite.getClass().getMethod("invokeMethod", String.class, Object.class);
    } catch (Exception e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  protected <E> E invoke(final String methodName, final Object... arguments) {
    try {
      return (E) invokeMethod.invoke(testSuite, methodName, arguments);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static class ToStringMatcher<E> implements ArgumentMatcher<E> {

    private final String value;

    public ToStringMatcher(final String value) {
      this.value = value;
    }

    @Override
    public boolean matches(final E argument) {
      return argument != null && value.equals(argument.toString());
    }

    @Override
    public String toString() {
      return "toString(" + value + ")";
    }
  }

  public <E extends CharSequence> E toString(final String value) {
    return argThat(new ToStringMatcher<>(value));
  }
}
