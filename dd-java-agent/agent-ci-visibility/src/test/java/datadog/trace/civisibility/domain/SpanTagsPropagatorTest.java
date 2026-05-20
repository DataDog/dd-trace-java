package datadog.trace.civisibility.domain;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.domain.SpanTagsPropagator.TagMergeSpec;
import datadog.trace.civisibility.ipc.TestFramework;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SpanTagsPropagatorTest {

  @ParameterizedTest
  @MethodSource("frameworkTags")
  void testGetFrameworks(
      Object frameworkTag,
      Object frameworkVersionTag,
      Collection<TestFramework> expectedFrameworks) {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(Tags.TEST_FRAMEWORK)).thenReturn(frameworkTag);
    when(span.getTag(Tags.TEST_FRAMEWORK_VERSION)).thenReturn(frameworkVersionTag);

    Collection<TestFramework> frameworks = SpanTagsPropagator.getFrameworks(span);

    assertEquals(expectedFrameworks, frameworks);
  }

  private static Stream<Arguments> frameworkTags() {
    return Stream.of(
        Arguments.of("name", "version", singletonList(new TestFramework("name", "version"))),
        Arguments.of("name", null, singletonList(new TestFramework("name", null))),
        Arguments.of(null, "version", emptyList()),
        Arguments.of(
            asList("nameA", "nameB"),
            asList("versionA", "versionB"),
            asList(new TestFramework("nameA", "versionA"), new TestFramework("nameB", "versionB"))),
        Arguments.of(
            asList("nameA", "nameB"),
            null,
            asList(new TestFramework("nameA", null), new TestFramework("nameB", null))),
        Arguments.of(
            asList("nameA", "nameB"),
            asList("versionA", null),
            asList(new TestFramework("nameA", "versionA"), new TestFramework("nameB", null))));
  }

  @ParameterizedTest
  @MethodSource("statusPropagations")
  void testStatusPropagation(
      TestStatus childStatus, TestStatus parentStatus, TestStatus expectedStatus) {
    AgentSpan parentSpan = mock(AgentSpan.class);
    when(parentSpan.getTag(Tags.TEST_STATUS)).thenReturn(parentStatus);

    AgentSpan childSpan = mock(AgentSpan.class);
    when(childSpan.getTag(Tags.TEST_STATUS)).thenReturn(childStatus);

    SpanTagsPropagator propagator = new SpanTagsPropagator(parentSpan);

    propagator.propagateStatus(childSpan);

    if (expectedStatus != null) {
      verify(parentSpan).setTag(Tags.TEST_STATUS, expectedStatus);
    } else {
      verify(parentSpan, never()).setTag(eq(Tags.TEST_STATUS), isA(Object.class));
    }
  }

  private static Stream<Arguments> statusPropagations() {
    return Stream.of(
        Arguments.of(TestStatus.pass, null, TestStatus.pass),
        Arguments.of(TestStatus.pass, TestStatus.skip, TestStatus.pass),
        Arguments.of(TestStatus.pass, TestStatus.pass, null),
        Arguments.of(TestStatus.pass, TestStatus.fail, null),
        Arguments.of(TestStatus.fail, null, TestStatus.fail),
        Arguments.of(TestStatus.fail, TestStatus.pass, TestStatus.fail),
        Arguments.of(TestStatus.fail, TestStatus.skip, TestStatus.fail),
        Arguments.of(TestStatus.fail, TestStatus.fail, TestStatus.fail),
        Arguments.of(TestStatus.skip, null, TestStatus.skip),
        Arguments.of(TestStatus.skip, TestStatus.pass, null),
        Arguments.of(TestStatus.skip, TestStatus.fail, null),
        Arguments.of(TestStatus.skip, TestStatus.skip, null),
        Arguments.of(null, TestStatus.pass, null));
  }

  @ParameterizedTest
  @MethodSource("frameworkMerges")
  void testFrameworkMerging(
      Collection<TestFramework> childFrameworks,
      Collection<TestFramework> parentFrameworks,
      Collection<TestFramework> expectedFrameworks) {
    AgentSpan parentSpan = mock(AgentSpan.class);
    when(parentSpan.getTag(Tags.TEST_FRAMEWORK)).thenReturn(names(parentFrameworks));
    when(parentSpan.getTag(Tags.TEST_FRAMEWORK_VERSION)).thenReturn(versions(parentFrameworks));

    SpanTagsPropagator propagator = new SpanTagsPropagator(parentSpan);
    Map<String, Collection<String>> expectedTags = new HashMap<>();
    expectedTags.put(Tags.TEST_FRAMEWORK, names(expectedFrameworks));
    expectedTags.put(Tags.TEST_FRAMEWORK_VERSION, versions(expectedFrameworks));

    propagator.mergeTestFrameworks(childFrameworks);

    verify(parentSpan).setAllTags(expectedTags);
  }

  private static Stream<Arguments> frameworkMerges() {
    return Stream.of(
        Arguments.of(
            emptyList(),
            asList(new TestFramework("JUnit", "5.8.0"), new TestFramework("TestNG", "7.4.0")),
            asList(new TestFramework("JUnit", "5.8.0"), new TestFramework("TestNG", "7.4.0"))),
        Arguments.of(
            asList(new TestFramework("JUnit", "5.8.0"), new TestFramework("TestNG", "7.4.0")),
            emptyList(),
            asList(new TestFramework("JUnit", "5.8.0"), new TestFramework("TestNG", "7.4.0"))),
        Arguments.of(
            asList(new TestFramework("JUnit", "5.8.0"), new TestFramework("TestNG", "7.4.0")),
            singletonList(new TestFramework("Spock", "2.3")),
            asList(
                new TestFramework("JUnit", "5.8.0"),
                new TestFramework("Spock", "2.3"),
                new TestFramework("TestNG", "7.4.0"))));
  }

  @ParameterizedTest
  @MethodSource("tagPropagations")
  <T> void testTagPropagation(
      TagMergeSpec<T> tagSpec,
      T childValue,
      T parentValue,
      boolean expectedChange,
      T expectedValue) {
    AgentSpan parentSpan = mock(AgentSpan.class);
    when(parentSpan.getTag("tag")).thenReturn(parentValue);

    AgentSpan childSpan = mock(AgentSpan.class);
    when(childSpan.getTag("tag")).thenReturn(childValue);

    SpanTagsPropagator propagator = new SpanTagsPropagator(parentSpan);

    propagator.propagateTags(childSpan, tagSpec);

    if (expectedChange) {
      verify(parentSpan).setTag("tag", expectedValue);
    } else {
      verify(parentSpan, never()).setTag(eq("tag"), isA(Object.class));
    }
  }

  private static Stream<Arguments> tagPropagations() {
    return Stream.of(
        Arguments.of(TagMergeSpec.of("tag"), "a", "b", true, "a"),
        Arguments.of(TagMergeSpec.of("tag"), null, "b", false, "b"),
        Arguments.of(TagMergeSpec.of("tag"), null, null, false, null),
        Arguments.of(TagMergeSpec.of("tag", Boolean::logicalOr), true, false, true, true),
        Arguments.of(TagMergeSpec.of("tag", Boolean::logicalOr), false, false, true, false));
  }

  @ParameterizedTest
  @MethodSource("customTagSpanPropagations")
  void testCustomTagPropagationFromSpan(
      Collection<String> allowlist,
      String key,
      Object childValue,
      Object parentValue,
      Object expectedValue) {
    AgentSpan parentSpan = mock(AgentSpan.class);
    when(parentSpan.getTag(key)).thenReturn(parentValue);

    AgentSpan childSpan = mock(AgentSpan.class);
    when(childSpan.getTag(key)).thenReturn(childValue);

    SpanTagsPropagator propagator = new SpanTagsPropagator(parentSpan, allowlist);

    propagator.propagateCustomTags(childSpan);

    if (expectedValue != null) {
      verify(parentSpan).setTag(key, expectedValue);
    } else {
      verify(parentSpan, never()).setTag(eq(key), isA(Object.class));
    }
  }

  private static Stream<Arguments> customTagSpanPropagations() {
    return Stream.of(
        Arguments.of(singletonList("example.number"), "example.number", 1L, null, 1L),
        Arguments.of(singletonList("example.number"), "example.number", 2L, 1L, 2L),
        Arguments.of(singletonList("example.number"), "example.number", null, 1L, null),
        Arguments.of(singletonList("example.number"), "example.count", 4, null, null),
        Arguments.of(emptyList(), "example.number", 1L, null, null),
        Arguments.of(null, "example.number", 1L, null, null),
        Arguments.of(singletonList("example.flag"), "example.flag", true, null, true),
        Arguments.of(singletonList("example.ratio"), "example.ratio", 0.5d, null, 0.5d),
        Arguments.of(singletonList("example.label"), "example.label", "red", null, "red"));
  }

  @ParameterizedTest
  @MethodSource("customTagMapPropagations")
  void testCustomTagPropagationFromMap(
      Collection<String> allowlist, Map<String, Object> tags, int expectedSets) {
    AgentSpan parentSpan = mock(AgentSpan.class);
    SpanTagsPropagator propagator = new SpanTagsPropagator(parentSpan, allowlist);

    propagator.propagateCustomTags(tags);

    verify(parentSpan, times(expectedSets)).setTag(any(String.class), any(Object.class));
  }

  private static Stream<Arguments> customTagMapPropagations() {
    return Stream.of(
        Arguments.of(
            asList("example.number", "example.count"),
            mapOf("example.number", 1L, "example.count", 4),
            2),
        Arguments.of(singletonList("example.flag"), mapOf("example.flag", true), 1),
        Arguments.of(
            singletonList("example.number"), mapOf("example.number", 1L, "example.count", 4), 1),
        Arguments.of(singletonList("example.ratio"), Collections.emptyMap(), 0),
        Arguments.of(emptyList(), mapOf("example.ratio", 0.5d), 0),
        Arguments.of(null, mapOf("example.ratio", 0.5d), 0));
  }

  @Test
  void testSynchronizedPropagation() throws InterruptedException {
    AgentSpan parentSpan = mock(AgentSpan.class);
    SpanTagsPropagator propagator = new SpanTagsPropagator(parentSpan);
    int numThreads = 9;
    CountDownLatch latch = new CountDownLatch(numThreads);
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    List<Exception> exceptions = Collections.synchronizedList(new java.util.ArrayList<>());

    for (int i = 0; i < numThreads; i++) {
      final int index = i;
      executor.submit(
          () -> {
            try {
              switch (index % 3) {
                case 0:
                  AgentSpan childSpan = mock(AgentSpan.class);
                  when(childSpan.getTag(Tags.TEST_STATUS)).thenReturn(TestStatus.fail);
                  propagator.propagateStatus(childSpan);
                  break;
                case 1:
                  Collection<TestFramework> frameworks =
                      singletonList(new TestFramework("JUnit" + index, "5." + index));
                  propagator.mergeTestFrameworks(frameworks);
                  break;
                case 2:
                  AgentSpan customTagChildSpan = mock(AgentSpan.class);
                  String tagKey = "custom.tag." + index;
                  when(customTagChildSpan.getTag(tagKey)).thenReturn("value" + index);
                  propagator.propagateTags(customTagChildSpan, TagMergeSpec.of(tagKey));
                  break;
                default:
                  throw new IllegalStateException("Unexpected remainder");
              }
            } catch (Exception e) {
              exceptions.add(e);
            } finally {
              latch.countDown();
            }
          });
    }

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    executor.shutdown();

    assertTrue(exceptions.isEmpty());
    verify(parentSpan, times(3)).setTag(Tags.TEST_STATUS, TestStatus.fail);
    verify(parentSpan, times(3)).setAllTags(any());
    verify(parentSpan, times(3))
        .setTag(
            argThat((String key) -> key.startsWith("custom.tag.")),
            argThat((Object value) -> value.toString().startsWith("value")));
  }

  private static Collection<String> names(Collection<TestFramework> frameworks) {
    List<String> names = new java.util.ArrayList<>();
    for (TestFramework framework : frameworks) {
      names.add(framework.getName());
    }
    return names;
  }

  private static Collection<String> versions(Collection<TestFramework> frameworks) {
    List<String> versions = new java.util.ArrayList<>();
    for (TestFramework framework : frameworks) {
      versions.add(framework.getVersion());
    }
    return versions;
  }

  private static Map<String, Object> mapOf(String key, Object value) {
    Map<String, Object> map = new HashMap<>();
    map.put(key, value);
    return map;
  }

  private static Map<String, Object> mapOf(
      String firstKey, Object firstValue, String secondKey, Object secondValue) {
    Map<String, Object> map = mapOf(firstKey, firstValue);
    map.put(secondKey, secondValue);
    return map;
  }
}
