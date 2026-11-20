package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.decorator.ExpectedSpanState.expectedSpan;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.Flow.Action.RequestBlockingAction;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ClientDecoratorTest extends BaseDecoratorTest {

  @TableTest({
    "scenario      | serviceName  ",
    "test service  | test-service ",
    "other service | other-service",
    "no service    |              "
  })
  void testClientAfterStart(String serviceName) {
    ClientDecorator decorator = newDecorator(serviceName);
    RecordingSpan recordingSpan = new RecordingSpan();

    // when
    decorator.afterStart(recordingSpan);

    // then
    ExpectedSpanState expected =
        expectedSpan()
            .spanType(decorator.spanType())
            .component("test-component")
            .spanKind("client")
            .measured(true)
            .analyticsSampleRate(1.0d);
    if (serviceName != null) {
      expected.serviceName(serviceName, "test-component");
    }
    // Polymorphic parent spec: subclass decorators (e.g. DB-type processing) layer on extra tags in
    // afterStart, so tolerate additional tags while asserting the client-level scalars exactly.
    expected.assertAppliedAllowingExtraTags(recordingSpan);
  }

  @Test
  void testClientBeforeFinish() {
    // when
    newDecorator("test-service").beforeFinish(span);

    // then
    verifyNoMoreInteractions(span);
  }

  /** A {@link Flow} whose action is the given blocking action. */
  static Flow<Void> blockingFlow(RequestBlockingAction action) {
    return new Flow<Void>() {
      @Override
      public Action getAction() {
        return action;
      }

      @Override
      public Void getResult() {
        return null;
      }
    };
  }

  @Override
  protected ClientDecorator newDecorator() {
    return newDecorator("test-service");
  }

  protected ClientDecorator newDecorator(String serviceName) {
    return new ClientDecorator() {
      @Override
      protected String[] instrumentationNames() {
        return new String[] {"test1", "test2"};
      }

      @Override
      protected String service() {
        return serviceName;
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
