package datadog.trace.instrumentation.testng;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.testng.ClassMethodMap;
import org.testng.IMethodInstance;
import org.testng.ITestClass;
import org.testng.ITestNGMethod;

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
 *
 * <p>The listener tries to replicate standard class events logic as closely as possible, the only
 * exception being {@code onAfterClass()} listener invocation time.
 */
public abstract class TestNGClassListener {

  public void onBeforeClass(
      ITestClass testClass, ClassMethodMap classMethodMap, IMethodInstance methodInstance) {
    if (isFirstMethodInSuite(testClass, classMethodMap, methodInstance)) {
      onBeforeClass(testClass);
    }
  }

  public void onAfterClass(
      ITestClass testClass, ClassMethodMap classMethodMap, IMethodInstance methodInstance) {
    if (isLastMethodInSuite(testClass, classMethodMap, methodInstance)) {
      onAfterClass(testClass);
    }
  }

  private boolean isFirstMethodInSuite(
      ITestClass testClass, ClassMethodMap classMethodMap, IMethodInstance methodInstance) {
    Map<ITestClass, Set<Object>> invokedBeforeClassMethods =
        classMethodMap.getInvokedBeforeClassMethods();
    synchronized (testClass) {
      // synchronization on testClass is the protocol used by TestNG
      Set<Object> instances =
          invokedBeforeClassMethods.computeIfAbsent(testClass, k -> new HashSet<>());
      Object instance = methodInstance.getInstance();

      ITestNGMethod[] beforeClassMethods = testClass.getBeforeClassMethods();
      if (beforeClassMethods == null || beforeClassMethods.length == 0) {
        // TestNG will not bother filling the set in this case, so we have to do it
        return instances.add(instance);
      } else {
        return !instances.contains(instance);
      }
    }
  }

  private boolean isLastMethodInSuite(
      ITestClass testClass, ClassMethodMap classMethodMap, IMethodInstance methodInstance) {
    ITestNGMethod[] afterClassMethods = testClass.getAfterClassMethods();
    if (afterClassMethods == null || afterClassMethods.length == 0) {
      // TestNG will not bother cleaning the map in this case, so we have to do it
      ITestNGMethod testNGMethod = methodInstance.getMethod();
      Object instance = methodInstance.getInstance();
      return classMethodMap.removeAndCheckIfLast(testNGMethod, instance);
    } else {
      // TestNG will clean the map.
      // We create a dummy method and "remove" it,
      // checking that no methods from the same testClass remain in the map
      ITestNGMethod testNGMethod = new TestNGMethod(testClass);
      Object instance = methodInstance.getInstance();
      return classMethodMap.removeAndCheckIfLast(testNGMethod, instance);
    }
  }

  protected abstract void onBeforeClass(ITestClass testClass);

  protected abstract void onAfterClass(ITestClass testClass);
}
