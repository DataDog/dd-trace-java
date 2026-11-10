package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.instrumentation.aerospike4.AerospikeClientDecorator.DECORATE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.junit.jupiter.api.Test;

class AerospikeClientDecoratorTest {

  /** The getNode advice also runs when getNode throws, so there may be no node. */
  @Test
  void tagsNoPeerWithoutANode() {
    AgentSpan span = mock(AgentSpan.class);

    DECORATE.onConnection(span, null, null, null);

    verifyNoInteractions(span);
  }
}
