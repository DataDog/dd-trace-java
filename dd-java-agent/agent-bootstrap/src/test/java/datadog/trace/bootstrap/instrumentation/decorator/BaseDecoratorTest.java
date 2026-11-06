package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOSTNAME;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOST_IPV4;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOST_IPV6;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_PORT;
import static datadog.trace.bootstrap.instrumentation.decorator.ExpectedSpanState.expectedSpan;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.appsec.api.blocking.BlockingException;
import datadog.context.Context;
import datadog.trace.api.KnownTagCodec;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.tabletest.junit.TableTest;

/**
 * Base of the decorator test hierarchy. Subclasses override {@link #newDecorator()} so the tests
 * declared here run again against every subclass decorator.
 */
class BaseDecoratorTest extends DDJavaSpecification {

  protected BaseDecorator decorator;

  /**
   * Priority expected on {@code addThrowable}; {@code null} means {@link ErrorPriorities#DEFAULT}.
   */
  protected Byte errorPriority;

  protected AgentSpan span;

  @BeforeAll
  static void setupConfigInversionStrictness() {
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST);
  }

  @BeforeEach
  void setupDecoratorAndSpan() {
    decorator = newDecorator();
    span = ((NameKeyedAgentSpan) mockSpan()).ofKind(spanKind());
  }

  /** The kind of the spans the decorator under test makes, which names its projected tags. */
  protected int spanKind() {
    return KnownTagCodec.KIND_UNSET;
  }

  /**
   * Mocks an {@link AgentSpan} that reports every id-keyed {@code setTag} as the name-keyed call,
   * so expectations are written by tag name however the code under test sets the tag. See {@link
   * NameKeyedAgentSpan}.
   */
  static AgentSpan mockSpan() {
    return mock(NameKeyedAgentSpan.class);
  }

  @Test
  void testAfterStart() {
    RecordingSpan recordingSpan = new RecordingSpan();

    // when
    decorator.afterStart(recordingSpan);

    // then
    // The base spec runs polymorphically against every subclass decorator, so it only asserts the
    // baseline identity every decorator applies, tolerating the tags subclasses layer on. Each
    // level's exact tag set is asserted by its own afterStart spec.
    expectedSpan()
        .spanType(decorator.spanType())
        .component("test-component")
        .assertIdentityAppliedTo(recordingSpan);
  }

  @TableTest({
    "scenario        | host              | port | resolved",
    "localhost       | localhost         | 888  | true    ",
    "ipv6 host       | ipv6.google.com   | 999  | true    ",
    "unresolved host | bad.address.local | 999  | false   "
  })
  void testOnPeerConnection(String host, int port, boolean resolved) {
    InetSocketAddress connection =
        resolved
            ? new InetSocketAddress(host, port)
            : InetSocketAddress.createUnresolved(host, port);

    // when
    decorator.onPeerConnection(span, connection);

    // then
    if (!connection.isUnresolved()) {
      verify(span).setTag(PEER_HOSTNAME, connection.getHostName());
    }
    verify(span).setTag(PEER_PORT, connection.getPort());
    InetAddress address = connection.getAddress();
    if (address instanceof Inet4Address) {
      verify(span).setTag(PEER_HOST_IPV4, address.getHostAddress());
    }
    if (address instanceof Inet6Address) {
      verify(span).setTag(PEER_HOST_IPV6, address.getHostAddress());
    }
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario   | withError",
    "with error | true     ",
    "no error   | false    "
  })
  void testOnError(boolean withError) {
    Exception error = withError ? new Exception() : null;

    // when
    decorator.onError(span, error);

    // then
    if (error != null) {
      verify(span)
          .addThrowable(error, errorPriority != null ? errorPriority : ErrorPriorities.DEFAULT);
    }
    verifyNoMoreInteractions(span);
  }

  @Test
  void testBeforeFinish() {
    // when
    decorator.beforeFinish(span);

    // then
    verify(span, atMost(1)).getLocalRootSpan();
    verify(span, atLeast(0)).get(any());
    verifyNoMoreInteractions(span);
  }

  @Test
  void testOnErrorSwallowsAThrowingDecorator() {
    BaseDecorator throwing = newThrowingDecorator(new RuntimeException("boom"));

    assertDoesNotThrow(() -> throwing.onError(span, new IllegalStateException("original")));
  }

  @Test
  void testBeforeFinishSwallowsAThrowingDecorator() {
    BaseDecorator throwing = newThrowingDecorator(new RuntimeException("boom"));

    assertDoesNotThrow(() -> throwing.beforeFinish(span));
  }

  @Test
  void testOnErrorRethrowsBlockingException() {
    BlockingException blocking = new BlockingException("blocked");
    BaseDecorator throwing = newThrowingDecorator(blocking);

    BlockingException caught =
        assertThrows(
            BlockingException.class,
            () -> throwing.onError(span, new IllegalStateException("original")));
    assertSame(blocking, caught);
  }

  @Test
  void testBeforeFinishRethrowsBlockingException() {
    BlockingException blocking = new BlockingException("blocked");
    BaseDecorator throwing = newThrowingDecorator(blocking);

    BlockingException caught =
        assertThrows(BlockingException.class, () -> throwing.beforeFinish(span));
    assertSame(blocking, caught);
  }

  @TableTest({
    "scenario                    | defaultEnabled | hasConfigNames | sampleRate",
    "default enabled             | true           | false          | 1.0       ",
    "default disabled            | false          | false          | 1.0       ",
    "empty instrumentation names | false          | true           | 1.0       "
  })
  void testAnalyticsRateDefaultDisabled(
      boolean defaultEnabled, boolean hasConfigNames, double sampleRate) {
    // when
    BaseDecorator dec = newDecorator(defaultEnabled, hasConfigNames);

    // then
    assertEquals(defaultEnabled, dec.traceAnalyticsEnabled);
    assertEquals(sampleRate, dec.traceAnalyticsSampleRate);
  }

  @TableTest({
    "scenario                        | enabled | integName | sampleRate | expectedEnabled | expectedRate",
    "disabled without integration    | false   | ''        | ''         | false           | 1.0         ",
    "enabled without integration     | true    | ''        | ''         | true            | 1.0         ",
    "disabled with test1 rate        | false   | test1     | 0.5        | true            | 0.5         ",
    "disabled with test2 rate        | false   | test2     | 0.75       | true            | 0.75        ",
    "enabled with test1 rate         | true    | test1     | 0.2        | true            | 0.2         ",
    "enabled with test2 rate         | true    | test2     | 0.4        | true            | 0.4         ",
    "enabled with test1 without rate | true    | test1     | ''         | true            | 1.0         ",
    "enabled with test2 without rate | true    | test2     | ''         | true            | 1.0         "
  })
  void testAnalyticsRateEnabled(
      boolean enabled,
      String integName,
      String sampleRate,
      boolean expectedEnabled,
      float expectedRate) {
    injectSysConfig("dd." + integName + ".analytics.enabled", "true");
    injectSysConfig("dd." + integName + ".analytics.sample-rate", sampleRate);

    // when
    BaseDecorator dec = newDecorator(enabled);

    // then
    assertEquals(expectedEnabled, dec.traceAnalyticsEnabled);
    assertEquals((double) expectedRate, dec.traceAnalyticsSampleRate);
  }

  @TableTest({
    "scenario        | target                                                                              | name             ",
    "inner class     | datadog.trace.bootstrap.instrumentation.decorator.BaseDecoratorTest$SomeInnerClass  | SomeInnerClass   ",
    "nested class    | datadog.trace.bootstrap.instrumentation.decorator.BaseDecoratorTest$SomeNestedClass | SomeNestedClass  ",
    "anonymous class | datadog.trace.bootstrap.instrumentation.decorator.SampleJavaClass$1                 | SampleJavaClass$1"
  })
  void testSpanNameForMethod(Class<?> target, String name) throws NoSuchMethodException {
    Method method = target.getDeclaredMethod("run");

    // when
    CharSequence result = decorator.spanNameForMethod(method);

    // then
    assertEquals(name + ".run", result.toString());
  }

  /** Builds a request map with the given HTTP method and URL. */
  static Map<String, Object> request(String method, URI url) {
    Map<String, Object> request = new HashMap<>();
    request.put("method", method);
    request.put("url", url);
    return request;
  }

  /** Matches a {@link CharSequence} argument whose text is {@code expected}. */
  static CharSequence hasText(String expected) {
    return argThat(value -> value != null && expected.equals(value.toString()));
  }

  /**
   * Marks every invocation of {@code methodName} with {@code argumentCount} arguments as verified,
   * whatever the overload, so {@code verifyNoMoreInteractions} tolerates them.
   */
  static void ignoreInteractions(Object mock, String methodName, int argumentCount) {
    for (Invocation invocation : mockingDetails(mock).getInvocations()) {
      if (methodName.equals(invocation.getMethod().getName())
          && invocation.getArguments().length == argumentCount) {
        invocation.markVerified();
      }
    }
  }

  /** Asserts that no {@code setTag} overload was called on {@code span} for {@code tag}. */
  static void assertTagNotSet(AgentSpan span, String tag) {
    for (Invocation invocation : mockingDetails(span).getInvocations()) {
      if ("setTag".equals(invocation.getMethod().getName())
          && invocation.getArguments().length == 2
          && tag.equals(invocation.getArgument(0))) {
        fail("Unexpected tag " + tag + " set: " + invocation);
      }
    }
  }

  protected BaseDecorator newDecorator() {
    return newDecorator(false);
  }

  protected BaseDecorator newDecorator(boolean analyticsEnabledDefault) {
    return newDecorator(analyticsEnabledDefault, false);
  }

  protected BaseDecorator newDecorator(
      boolean analyticsEnabledDefault, boolean emptyInstrumentationNames) {
    String[] names = emptyInstrumentationNames ? new String[0] : new String[] {"test1", "test2"};
    boolean analyticsDefault = emptyInstrumentationNames || analyticsEnabledDefault;
    return new BaseDecorator() {
      @Override
      protected String[] instrumentationNames() {
        return names;
      }

      @Override
      protected CharSequence spanType() {
        return "test-type";
      }

      @Override
      protected CharSequence component() {
        return "test-component";
      }

      @Override
      protected boolean traceAnalyticsDefault() {
        return analyticsDefault;
      }
    };
  }

  protected BaseDecorator newThrowingDecorator(RuntimeException toThrow) {
    return new BaseDecorator() {
      @Override
      protected String[] instrumentationNames() {
        return new String[] {"test1", "test2"};
      }

      @Override
      protected CharSequence spanType() {
        return "test-type";
      }

      @Override
      protected CharSequence component() {
        return "test-component";
      }

      @Override
      protected void doOnError(
          @Nonnull AgentSpan span, @Nonnull Throwable throwable, byte errorPriority) {
        throw toThrow;
      }

      @Override
      protected void doBeforeFinish(@Nonnull Context context) {
        throw toThrow;
      }
    };
  }

  class SomeInnerClass implements Runnable {
    @Override
    public void run() {}
  }

  static class SomeNestedClass implements Runnable {
    @Override
    public void run() {}
  }
}
