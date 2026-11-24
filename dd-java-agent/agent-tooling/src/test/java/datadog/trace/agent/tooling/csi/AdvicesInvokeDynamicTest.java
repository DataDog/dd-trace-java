package datadog.trace.agent.tooling.csi;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.condition.JRE.JAVA_9;
import static org.mockito.Mockito.mock;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

@EnabledForJreRange(min = JAVA_9)
class AdvicesInvokeDynamicTest extends BaseCallSiteTest {

  @TableTest({
    "scenario                                    | pointcut            | emptyAdvices | adviceFound",
    "String.concat                               | stringConcat        | true         | false      ",
    "StringConcatFactory.makeConcatWithConstants | stringConcatFactory | false        | true       "
  })
  void testConstantPoolIntrospectorWithInvokeDynamic(
      @ConvertWith(PointcutConverter.class) Pointcut pointcut,
      boolean emptyAdvices,
      boolean adviceFound) {
    assertIntrospection(StringPlusExample.class, pointcut, emptyAdvices, adviceFound);
  }

  @TableTest({
    "scenario                                    | pointcut            | emptyAdvices | adviceFound",
    "String.concat                               | stringConcat        | true         | false      ",
    "StringConcatFactory.makeConcatWithConstants | stringConcatFactory | false        | true       "
  })
  void testConstantPoolIntrospectorWithInvokeDynamicAndConstants(
      @ConvertWith(PointcutConverter.class) Pointcut pointcut,
      boolean emptyAdvices,
      boolean adviceFound) {
    assertIntrospection(StringPlusConstantsExample.class, pointcut, emptyAdvices, adviceFound);
  }

  private static void assertIntrospection(
      Class<?> example, Pointcut pointcut, boolean emptyAdvices, boolean adviceFound) {
    // setup
    TypeDescription type = new TypeDescription.ForLoadedType(example);
    InvokeDynamicAdvice advice = mock(InvokeDynamicAdvice.class);
    Advices advices = Advices.fromCallSites(singletonList(mockCallSites(advice, pointcut)));
    Advices.AdviceIntrospector introspector =
        Advices.AdviceIntrospector.ConstantPoolInstrospector.INSTANCE;

    // when
    Advices result =
        introspector.findAdvices(
            advices, mock(DynamicType.Builder.class), type, example.getClassLoader());
    boolean found = result.findAdvice(pointcut.type, pointcut.method, pointcut.descriptor) != null;

    // then
    assertEquals(emptyAdvices, result.isEmpty());
    assertEquals(adviceFound, found);
  }
}
