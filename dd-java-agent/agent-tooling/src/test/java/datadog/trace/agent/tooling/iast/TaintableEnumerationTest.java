package datadog.trace.agent.tooling.iast;

import static datadog.trace.api.iast.SourceTypes.REQUEST_PARAMETER_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaintableEnumerationTest extends DDJavaSpecification {

  private static final IastContext IAST_CONTEXT = mock(IastContext.class);

  private PropagationModule module;

  @BeforeEach
  void setup() {
    module = mock(PropagationModule.class);
    InstrumentationBridge.registerIastModule(module);
  }

  @AfterEach
  void cleanup() {
    // only the propagation module is registered by this test
    InstrumentationBridge.PROPAGATION = null;
  }

  @Test
  void underlyingEnumeratedValuesAreTaintedWithAName() {
    // given
    List<String> values = values();
    byte origin = REQUEST_PARAMETER_NAME;
    String name = "test";
    Enumeration<String> enumeration =
        TaintableEnumeration.wrap(
            IAST_CONTEXT, Collections.enumeration(values), module, origin, name);

    // when
    List<String> result = Collections.list(enumeration);

    // then
    assertEquals(values, result);
    for (String value : values) {
      verify(module).taintString(IAST_CONTEXT, value, origin, name);
    }
  }

  @Test
  void underlyingEnumeratedValuesAreTaintedWithTheValueAsAName() {
    // given
    List<String> values = values();
    byte origin = REQUEST_PARAMETER_NAME;
    Enumeration<String> enumeration =
        TaintableEnumeration.wrap(
            IAST_CONTEXT, Collections.enumeration(values), module, origin, true);

    // when
    List<String> result = Collections.list(enumeration);

    // then
    assertEquals(values, result);
    for (String value : values) {
      verify(module).taintString(IAST_CONTEXT, value, origin, value);
    }
  }

  @Test
  void taintableEnumerationLeavesNoTraceInCaseOfError() {
    // given
    byte origin = REQUEST_PARAMETER_NAME;
    Enumeration<String> enumeration =
        TaintableEnumeration.wrap(IAST_CONTEXT, new BadEnumeration(), module, origin, true);

    // when
    Error first = assertThrows(Error.class, enumeration::hasMoreElements);

    // then
    String className = TaintableEnumeration.class.getName();
    assertTrue(
        Arrays.stream(first.getStackTrace())
            .noneMatch(element -> element.getClassName().equals(className)));
  }

  private static List<String> values() {
    List<String> values = new ArrayList<>();
    for (int index = 1; index <= 10; index++) {
      values.add("value" + index);
    }
    return values;
  }

  private static class BadEnumeration implements Enumeration<String> {
    @Override
    public boolean hasMoreElements() {
      throw new Error("Ooops!!!");
    }

    @Override
    public String nextElement() {
      throw new Error("Boom!!!");
    }
  }
}
