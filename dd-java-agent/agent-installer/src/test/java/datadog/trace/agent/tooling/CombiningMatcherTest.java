package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;

class CombiningMatcherTest {

  @Test
  void selectsOnlyTransformationsRegisteredForLambdaInterface() {
    TypeDescription target = new TypeDescription.ForLoadedType(getClass());
    ClassLoader classLoader = getClass().getClassLoader();
    LambdaMatchRecorder recorder =
        new LambdaMatchRecorder(3, named(getClass().getName()), loader -> loader == classLoader);
    recorder.addTransformation(7);

    Map<String, List<LambdaMatchRecorder>> lambdaMatchers =
        singletonMap(Runnable.class.getName(), singletonList(recorder));
    CombiningMatcher matcher =
        new CombiningMatcher(null, new BitSet(), emptyList(), lambdaMatchers);

    TypePoolFacade.beginLambdaTransform(Runnable.class.getName());
    try {
      assertTrue(matcher.matches(target, classLoader, null, null, null));
      assertTrue(CombiningMatcher.recordedMatches.get().get(3));
      assertTrue(CombiningMatcher.recordedMatches.get().get(7));
    } finally {
      TypePoolFacade.endLambdaTransform();
    }

    TypePoolFacade.beginLambdaTransform(java.util.function.Supplier.class.getName());
    try {
      assertFalse(matcher.matches(target, classLoader, null, null, null));
    } finally {
      TypePoolFacade.endLambdaTransform();
    }
  }
}
