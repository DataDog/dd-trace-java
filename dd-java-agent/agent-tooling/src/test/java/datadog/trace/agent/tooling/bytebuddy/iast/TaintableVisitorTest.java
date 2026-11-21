package datadog.trace.agent.tooling.bytebuddy.iast;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.api.iast.Taintable;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import org.junit.jupiter.api.Test;

class TaintableVisitorTest extends DDJavaSpecification {

  private static final String CLASS_NAME =
      "datadog.trace.agent.tooling.bytebuddy.iast.TaintableTest";

  @Test
  void testTaintableVisitor() throws Exception {
    // given
    Taintable.Source source = mock(Taintable.Source.class);
    DynamicType.Builder<Object> builder =
        new ByteBuddy()
            .subclass(Object.class)
            .name(CLASS_NAME)
            .merge(Visibility.PUBLIC)
            .visit(new TaintableVisitor(CLASS_NAME));

    // when
    Class<?> clazz = load(builder);

    // then
    assertNotNull(clazz);
    assertTrue(Arrays.asList(clazz.getInterfaces()).contains(Taintable.class));

    assertTaintable(clazz, source);
  }

  @Test
  void testTaintableVisitorWithExistingInterface() throws Exception {
    // given
    Taintable.Source source = mock(Taintable.Source.class);
    DynamicType.Builder<Cloneable> builder =
        new ByteBuddy()
            .subclass(Cloneable.class)
            .name(CLASS_NAME)
            .merge(Visibility.PUBLIC)
            .visit(new TaintableVisitor(CLASS_NAME));

    // when
    Class<?> clazz = load(builder);

    // then
    assertNotNull(clazz);
    List<Class<?>> interfaces = Arrays.asList(clazz.getInterfaces());
    assertTrue(interfaces.contains(Taintable.class));
    assertTrue(interfaces.contains(Cloneable.class));

    assertTaintable(clazz, source);
  }

  private static Class<?> load(DynamicType.Builder<?> builder) {
    return builder.make().load(Thread.currentThread().getContextClassLoader()).getLoaded();
  }

  private static void assertTaintable(Class<?> clazz, Taintable.Source source) throws Exception {
    // when
    Object instance = clazz.getDeclaredConstructor().newInstance();

    // then
    Taintable taintable = assertInstanceOf(Taintable.class, instance);
    assertNull(taintable.$$DD$getSource());

    // when
    taintable.$$DD$setSource(source);
    taintable.$$DD$getSource().getOrigin();

    // then
    verify(source).getOrigin();
  }
}
