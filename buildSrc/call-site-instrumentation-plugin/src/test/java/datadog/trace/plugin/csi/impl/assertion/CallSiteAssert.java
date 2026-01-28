package datadog.trace.plugin.csi.impl.assertion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class CallSiteAssert {

  protected Set<Class<?>> interfaces;
  protected Set<Class<?>> spi;
  protected Set<Class<?>> helpers;
  protected List<AdviceAssert> advices;
  protected Method enabled;
  protected Set<String> enabledArgs;

  public CallSiteAssert(
      Set<Class<?>> interfaces,
      Set<Class<?>> spi,
      Set<Class<?>> helpers,
      List<AdviceAssert> advices,
      Method enabled,
      Set<String> enabledArgs) {
    this.interfaces = interfaces;
    this.spi = spi;
    this.helpers = helpers;
    this.advices = advices;
    this.enabled = enabled;
    this.enabledArgs = enabledArgs;
  }

  public void interfaces(Class<?>... values) {
    assertSameElements(interfaces, values);
  }

  public void helpers(Class<?>... values) {
    assertSameElements(helpers, values);
  }

  public void spi(Class<?>... values) {
    assertSameElements(spi, values);
  }

  public void advices(int index, Consumer<AdviceAssert> assertions) {
    AdviceAssert asserter = advices.get(index);
    assertions.accept(asserter);
  }

  public void enabled(Method method, String... args) {
    assertEquals(method, enabled);
    assertSameElements(enabledArgs, args);
  }

  private static <E> void assertSameElements(Set<E> expected, E... received) {
    assertEquals(received.length, expected.size());
    Set<E> receivedSet = new HashSet<>(Arrays.asList(received));
    assertTrue(expected.containsAll(receivedSet) && receivedSet.containsAll(expected));
  }

  public Set<Class<?>> getInterfaces() {
    return interfaces;
  }

  public Set<Class<?>> getSpi() {
    return spi;
  }

  public Set<Class<?>> getHelpers() {
    return helpers;
  }

  public List<AdviceAssert> getAdvices() {
    return advices;
  }

  public Method getEnabled() {
    return enabled;
  }

  public Set<String> getEnabledArgs() {
    return enabledArgs;
  }
}
