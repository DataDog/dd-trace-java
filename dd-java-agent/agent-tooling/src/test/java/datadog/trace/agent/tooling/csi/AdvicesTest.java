package datadog.trace.agent.tooling.csi;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class AdvicesTest extends BaseCallSiteTest {

  @Test
  void testEmptyAdvices() {
    // setup
    Advices advices = Advices.fromCallSites();

    // when
    boolean empty = advices.isEmpty();

    // then
    assertTrue(empty);
    assertSame(Advices.EMPTY, advices);
  }

  @Test
  void testNonEmptyAdvices() throws NoSuchMethodException {
    // setup
    Pointcut pointcut = stringConcatPointcut();
    Advices advices = Advices.fromCallSites(mockCallSites(mock(InvokeAdvice.class), pointcut));

    // when
    boolean empty = advices.isEmpty();

    // then
    assertFalse(empty);
  }

  @Test
  void testAdvicesForType() throws NoSuchMethodException {
    // setup
    Pointcut pointcut = stringConcatPointcut();
    Advices advices = Advices.fromCallSites(mockCallSites(mock(InvokeAdvice.class), pointcut));

    // when
    CallSiteAdvice existing =
        advices.findAdvice(pointcut.type, pointcut.method, pointcut.descriptor);

    // then
    assertNotNull(existing);

    // when
    CallSiteAdvice notFound =
        advices.findAdvice("java/lang/Integer", pointcut.method, pointcut.descriptor);

    // then
    assertNull(notFound);
  }

  @Test
  void testMultipleAdvicesForType() throws NoSuchMethodException {
    // setup
    Pointcut startsWith1 =
        buildPointcut(String.class.getDeclaredMethod("startsWith", String.class));
    Pointcut startsWith2 =
        buildPointcut(String.class.getDeclaredMethod("startsWith", String.class, int.class));
    Advices advices =
        Advices.fromCallSites(
            mockCallSites(mock(InvokeAdvice.class), startsWith1),
            mockCallSites(mock(InvokeAdvice.class), startsWith2));

    // when
    CallSiteAdvice startsWith1Found =
        advices.findAdvice(startsWith1.type, startsWith1.method, startsWith1.descriptor);

    // then
    assertNotNull(startsWith1Found);

    // when
    CallSiteAdvice startsWith2Found =
        advices.findAdvice(startsWith2.type, startsWith2.method, startsWith2.descriptor);

    // then
    assertNotNull(startsWith2Found);
  }

  @Test
  void testHelperClassNames() throws NoSuchMethodException {
    // setup
    CallSites concatAdvice =
        mockCallSites(
            mock(InvokeAdvice.class), stringConcatPointcut(), "foo.bar.Helper1", "foo.bar.Helper2");
    CallSites digestAdvice =
        mockCallSites(
            mock(InvokeAdvice.class), messageDigestGetInstancePointcut(), "foo.bar.Helper3");
    Advices advices = Advices.fromCallSites(asList(concatAdvice, digestAdvice));

    // when
    String[] helpers = advices.getHelpers();

    // then
    assertTrue(
        asList(helpers)
            .containsAll(asList("foo.bar.Helper1", "foo.bar.Helper2", "foo.bar.Helper3")));
  }

  @Test
  void testAdvicesWithDuplicatedPointcut() throws NoSuchMethodException {
    // setup
    Pointcut pointcut = stringConcatPointcut();

    // when
    // then
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            Advices.fromCallSites(
                mockCallSites(mock(InvokeAdvice.class), pointcut),
                mockCallSites(mock(InvokeAdvice.class), pointcut)));
  }

  @Test
  void testNoOpAdviceInstrospector() {
    // setup
    Advices.AdviceIntrospector introspector =
        Advices.AdviceIntrospector.NoOpAdviceInstrospector.INSTANCE;
    Advices advices = Advices.fromCallSites();

    // when
    Advices result = introspector.findAdvices(advices, null, null, null, (Advices.Listener[]) null);

    // then
    assertSame(advices, result);
  }

  @TableTest({
    "scenario                  | pointcut                 | emptyAdvices | adviceFound",
    "String.concat             | stringConcat             | false        | true       ",
    "MessageDigest.getInstance | messageDigestGetInstance | true         | false      "
  })
  void testConstantPoolIntrospector(
      @ConvertWith(PointcutConverter.class) Pointcut pointcut,
      boolean emptyAdvices,
      boolean adviceFound) {
    // setup
    CallSites advice = mockCallSites(mock(InvokeAdvice.class), pointcut);
    Advices advices = Advices.fromCallSites(advice);
    Advices.AdviceIntrospector introspector =
        Advices.AdviceIntrospector.ConstantPoolInstrospector.INSTANCE;
    ClassLoader classLoader = StringConcatExample.class.getClassLoader();
    TypeDescription type = new TypeDescription.ForLoadedType(StringConcatExample.class);

    // when
    Advices result =
        introspector.findAdvices(advices, mock(DynamicType.Builder.class), type, classLoader);
    boolean found = result.findAdvice(pointcut.type, pointcut.method, pointcut.descriptor) != null;

    // then
    assertEquals(emptyAdvices, result.isEmpty());
    assertEquals(adviceFound, found);
  }
}
