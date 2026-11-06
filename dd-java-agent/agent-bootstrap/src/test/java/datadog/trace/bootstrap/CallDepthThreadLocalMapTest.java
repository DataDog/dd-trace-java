package datadog.trace.bootstrap;

import static datadog.trace.bootstrap.CallDepthThreadLocalMap.incrementCallDepth;
import static datadog.trace.bootstrap.CallDepthThreadLocalMap.reset;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;

class CallDepthThreadLocalMapTest extends DDJavaSpecification {

  @Test
  void testCallDepthThreadLocalMap() {
    // setup
    Class<?> stringKey = String.class;
    Class<?> integerKey = Integer.class;

    // expect
    assertEquals(0, incrementCallDepth(stringKey));
    assertEquals(0, incrementCallDepth(integerKey));

    assertEquals(1, incrementCallDepth(stringKey));
    assertEquals(1, incrementCallDepth(integerKey));

    // when
    reset(stringKey);

    // then
    assertEquals(2, incrementCallDepth(integerKey));

    // when
    reset(integerKey);

    // then
    assertEquals(0, incrementCallDepth(stringKey));
    assertEquals(0, incrementCallDepth(integerKey));

    assertEquals(1, incrementCallDepth(stringKey));
    assertEquals(1, incrementCallDepth(integerKey));
  }
}
