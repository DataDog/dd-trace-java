package datadog.trace.plugin.csi.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.plugin.csi.ValidationContext;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification;
import datadog.trace.plugin.csi.util.ErrorCode;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class CallSiteSpecificationTest extends BaseCsiPluginTest {

  @Test
  void testCallSiteSpiShouldBeAnInterface() {
    ValidationContext context = mockValidationContext();
    AdviceSpecification mockAdvice = mock(AdviceSpecification.class);
    List<AdviceSpecification> advices = Collections.singletonList(mockAdvice);
    Set<Type> spiTypes = Collections.singleton(Type.getType(String.class));
    List<String> helperClassNames = Collections.emptyList();
    Set<Type> constants = Collections.emptySet();
    CallSiteSpecification spec =
        new CallSiteSpecification(
            Type.getType(String.class), advices, spiTypes, helperClassNames, constants);

    spec.validate(context);

    verify(context).addError(eq(ErrorCode.CALL_SITE_SPI_SHOULD_BE_AN_INTERFACE), any());
  }

  @Test
  void testCallSiteSpiShouldNotDefineAnyMethods() {
    ValidationContext context = mockValidationContext();
    AdviceSpecification mockAdvice = mock(AdviceSpecification.class);
    List<AdviceSpecification> advices = Collections.singletonList(mockAdvice);
    Set<Type> spiTypes = Collections.singleton(Type.getType(Comparable.class));
    List<String> helperClassNames = Collections.emptyList();
    Set<Type> constants = Collections.emptySet();
    CallSiteSpecification spec =
        new CallSiteSpecification(
            Type.getType(String.class), advices, spiTypes, helperClassNames, constants);

    spec.validate(context);

    verify(context).addError(eq(ErrorCode.CALL_SITE_SPI_SHOULD_BE_EMPTY), any());
  }

  @Test
  void testCallSiteShouldHaveAdvices() {
    ValidationContext context = mockValidationContext();
    List<AdviceSpecification> advices = Collections.emptyList();
    Set<Type> spiTypes = Collections.singleton(Type.getType(CallSiteAdvice.class));
    List<String> helperClassNames = Collections.emptyList();
    Set<Type> constants = Collections.emptySet();
    CallSiteSpecification spec =
        new CallSiteSpecification(
            Type.getType(String.class), advices, spiTypes, helperClassNames, constants);

    spec.validate(context);

    verify(context).addError(eq(ErrorCode.CALL_SITE_SHOULD_HAVE_ADVICE_METHODS), any());
  }
}
