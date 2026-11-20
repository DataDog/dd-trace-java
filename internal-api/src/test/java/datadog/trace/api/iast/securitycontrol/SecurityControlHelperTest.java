package datadog.trace.api.iast.securitycontrol;

import static datadog.trace.api.iast.VulnerabilityMarks.XSS_MARK;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SecurityControlHelperTest {

  @AfterEach
  void tearDown() {
    InstrumentationBridge.PROPAGATION = null;
  }

  @Test
  void testNoModule() {
    String toValidate = "test";

    assertDoesNotThrow(() -> SecurityControlHelper.setSecureMarks(toValidate, XSS_MARK));
  }

  @Test
  void testSetSecureMarks() {
    PropagationModule iastModule = mock(PropagationModule.class);
    InstrumentationBridge.registerIastModule(iastModule);
    String toValidate = "test";

    SecurityControlHelper.setSecureMarks(toValidate, XSS_MARK);

    verify(iastModule).markIfTainted(toValidate, XSS_MARK);
    verifyNoMoreInteractions(iastModule);
  }
}
