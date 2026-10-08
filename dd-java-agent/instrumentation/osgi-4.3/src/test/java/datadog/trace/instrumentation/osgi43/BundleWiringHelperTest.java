package datadog.trace.instrumentation.osgi43;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.osgi.framework.wiring.BundleRevision.PACKAGE_NAMESPACE;

import java.util.ConcurrentModificationException;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleWiring;

class BundleWiringHelperTest {

  @Test
  void probeFallsBackToStandardLookupWhenWiringChangesConcurrently() {
    Bundle bundle = mock(Bundle.class);
    BundleWiring wiring = mock(BundleWiring.class);
    when(bundle.adapt(BundleWiring.class)).thenReturn(wiring);
    when(wiring.getRequiredWires(PACKAGE_NAMESPACE))
        .thenThrow(new ConcurrentModificationException());

    // null tells WidenGetResourceAdvice to let the original getResource call proceed
    assertNull(BundleWiringHelper.probeResource(bundle, "com/example/Foo.class"));
  }
}
