package datadog.trace.instrumentation.testng;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.testng.IMethodInstance;
import org.testng.ITestClass;
import org.testng.ITestNGMethod;
import org.testng.internal.ConstructorOrMethod;

/**
 * This is a custom class events handler that solves two problems:
 *
 * <ul>
 *   <li>TestNG before v6.9.13.3 does not have class listeners mechanism
 *   <li>TestNG after v6.9.13.3 has class listeners mechanism ({@code IClassListener} interface),
 *       but it has a shortcoming: {@code onAfterClass()} methods are invoked BEFORE methods
 *       annotated with {@code @AfterClass} are executed.
 * </ul>
 *
 * As the result, {@code @AfterClass} methods' execution time will not be reflected in overall test
 * suite running time, if standard listeners are used.
 *
 * <p>The other problem is that if an {@code @AfterClass} method throws an exception, standard
 * listener will be notified BEFORE this exception is thrown and will have no way of reacting to it.
 */
public abstract class TestNGClassListener {

  private final ConcurrentMap<Class<?>, Collection<ConstructorOrMethod>> registeredMethods =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<Class<?>, Collection<ConstructorOrMethod>> methodsAwaitingExecution =
      new ConcurrentHashMap<>();

  public void registerTestMethods(Collection<ITestNGMethod> testMethods) {
    for (ITestNGMethod testMethod : testMethods) {
      ITestClass testClass = testMethod.getTestClass();
      Class<?> realClass = testClass.getRealClass();
      ConstructorOrMethod constructorOrMethod = testMethod.getConstructorOrMethod();
      registeredMethods.computeIfAbsent(realClass, k -> new ArrayList<>()).add(constructorOrMethod);
    }
  }

  public void invokeBeforeClass(ITestClass testClass, boolean parallelized) {
    methodsAwaitingExecution.computeIfAbsent(
        testClass.getRealClass(),
        k -> {
          // firing event with the lock held to ensure that the other threads wait until test suite
          // state is initialized
          onBeforeClass(testClass, parallelized);
          return registeredMethods.remove(k);
        });
  }

  public void invokeAfterClass(ITestClass testClass, IMethodInstance methodInstance) {
    Collection<ConstructorOrMethod> remainingMethods =
        methodsAwaitingExecution.computeIfPresent(
            testClass.getRealClass(),
            (k, v) -> {
              ITestNGMethod method = methodInstance.getMethod();
              ConstructorOrMethod constructorOrMethod = method.getConstructorOrMethod();
              v.remove(constructorOrMethod);
              return !v.isEmpty() ? v : null;
            });

    if (remainingMethods == null) {
      onAfterClass(testClass);
    }
  }

  protected abstract void onBeforeClass(ITestClass testClass, boolean parallelized);

  protected abstract void onAfterClass(ITestClass testClass);
}
