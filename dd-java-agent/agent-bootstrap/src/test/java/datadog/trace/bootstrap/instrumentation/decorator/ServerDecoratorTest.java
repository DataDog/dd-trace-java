package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.decorator.ExpectedSpanState.expectedSpan;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class ServerDecoratorTest extends BaseDecoratorTest {

  @Test
  void testServerAfterStart() {
    ServerDecorator decorator = newDecorator();
    RecordingSpan recordingSpan = new RecordingSpan();

    // when
    decorator.afterStart(recordingSpan);

    // then
    expectedSpan()
        .spanType(decorator.spanType())
        .component("test-component")
        .spanKind("server")
        .language()
        .analyticsSampleRate(decorator.traceAnalyticsEnabled ? 1.0d : null)
        .assertAppliedTo(recordingSpan);
  }

  @Test
  void testServerBeforeFinish() {
    // when
    newDecorator().beforeFinish(span);

    // then
    verify(span, atMost(1)).getLocalRootSpan();
  }

  @Override
  protected ServerDecorator newDecorator() {
    return new ServerDecorator() {
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
      protected boolean traceAnalyticsDefault() {
        return true;
      }
    };
  }
}
