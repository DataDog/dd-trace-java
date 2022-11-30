package datadog.trace.agent.test;

import net.bytebuddy.asm.Advice;

public class SputnikAdvice {

  public static final String AGENT_TEST_RUNNER = "datadog.trace.agent.test.AgentTestRunner";

  @Advice.OnMethodEnter
  public static void sputnikConstructor(
      @Advice.Argument(value = 0, readOnly = false) Class<?> clazz) {
    clazz = maybeShadowTestClass(clazz);
  }

  public static Class<?> maybeShadowTestClass(final Class<?> clazz) {
    if (classExtends(clazz, AGENT_TEST_RUNNER)) {
      try {
        final InstrumentationClassLoader customLoader =
            new InstrumentationClassLoader(clazz.getClassLoader(), clazz.getName());
        return customLoader.shadow(clazz);
      } catch (final Exception e) {
        throw new IllegalStateException(e);
      }
    }
    return clazz;
  }

  public static boolean classExtends(final Class<?> clazz, final String expected) {
    Class<?> cur = clazz;
    while ((cur = cur.getSuperclass()) != Object.class) {
      if (expected.equals(cur.getSimpleName())) {
        return true;
      }
    }
    return false;
  }
}
