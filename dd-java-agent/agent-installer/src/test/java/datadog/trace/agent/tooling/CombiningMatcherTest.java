package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Arrays.asList;
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
    recorder.addTransformation(7, named(getClass().getName()), "matching context store");
    recorder.addTransformation(11, named(String.class.getName()), "unrelated context store");
    recorder.addTransformation(
        13,
        ignored -> {
          throw new IllegalStateException("broken conditional matcher");
        },
        "broken context store");
    recorder.addTransformation(17, named(getClass().getName()), "later context store");

    Map<String, List<LambdaMatchRecorder>> lambdaMatchers =
        singletonMap(Runnable.class.getName(), singletonList(recorder));
    CombiningMatcher matcher =
        new CombiningMatcher(null, new BitSet(), emptyList(), lambdaMatchers);

    TypePoolFacade.beginLambdaTransform(Runnable.class.getName());
    try {
      assertTrue(matcher.matches(target, classLoader, null, null, null));
      assertTrue(CombiningMatcher.recordedMatches.get().get(3));
      assertTrue(CombiningMatcher.recordedMatches.get().get(7));
      assertFalse(CombiningMatcher.recordedMatches.get().get(11));
      assertFalse(CombiningMatcher.recordedMatches.get().get(13));
      assertTrue(CombiningMatcher.recordedMatches.get().get(17));
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

  @Test
  void isolatesFailuresBetweenLambdaMatchers() {
    TypeDescription target = new TypeDescription.ForLoadedType(getClass());
    ClassLoader classLoader = getClass().getClassLoader();
    LambdaMatchRecorder broken =
        new LambdaMatchRecorder(
            3,
            ignored -> {
              throw new IllegalStateException("broken matcher");
            },
            ignored -> true);
    LambdaMatchRecorder working =
        new LambdaMatchRecorder(7, named(getClass().getName()), ignored -> true);

    CombiningMatcher matcher =
        new CombiningMatcher(
            null,
            new BitSet(),
            emptyList(),
            singletonMap(Runnable.class.getName(), asList(broken, working)));

    TypePoolFacade.beginLambdaTransform(Runnable.class.getName());
    try {
      assertTrue(matcher.matches(target, classLoader, null, null, null));
      assertFalse(CombiningMatcher.recordedMatches.get().get(3));
      assertTrue(CombiningMatcher.recordedMatches.get().get(7));
    } finally {
      TypePoolFacade.endLambdaTransform();
    }
  }
}
