package datadog.trace.instrumentation.osgi43;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
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

  @Test
  void probeFallsBackToStandardLookupWhenBundleIsUninstalled() {
    Bundle bundle = uninstalledBundle();

    // null tells WidenGetResourceAdvice to let the original getResource call proceed
    assertNull(BundleWiringHelper.probeResource(bundle, "com/example/Foo.class"));
  }

  @Test
  void probeFallsBackToStandardLookupWhenBundleIsUninstalledDuringProbe() {
    Bundle bundle = mock(Bundle.class);
    when(bundle.adapt(BundleWiring.class))
        .thenThrow(new IllegalStateException("Bundle has been uninstalled"));

    assertNull(BundleWiringHelper.probeResource(bundle, "com/example/Foo.class"));
  }

  /** OSGi has adapt return null, not throw, for a bundle uninstalled after getEntry. */
  @Test
  void probeFallsBackToStandardLookupWhenWiringIsGoneAfterUninstall() {
    Bundle bundle = mock(Bundle.class);
    when(bundle.adapt(BundleWiring.class)).thenReturn(null);
    when(bundle.getState()).thenReturn(Bundle.UNINSTALLED);

    assertNull(BundleWiringHelper.probeResource(bundle, "com/example/Foo.class"));
  }

  /** An installed bundle that isn't resolved yet has no wiring and imports nothing. */
  @Test
  void probeSkipsTheLookupForAnUnresolvedBundle() {
    Bundle bundle = mock(Bundle.class);
    when(bundle.adapt(BundleWiring.class)).thenReturn(null);
    when(bundle.getState()).thenReturn(Bundle.INSTALLED);

    assertNotNull(BundleWiringHelper.probeResource(bundle, "com/example/Foo.class"));
  }

  @Test
  void getResourceFindsNothingWhenBundleIsUninstalled() {
    assertNull(BundleWiringHelper.getResource(uninstalledBundle(), "com/example/Foo.class"));
  }

  @Test
  void loadClassFindsNothingWhenBundleIsUninstalled() {
    assertNull(BundleWiringHelper.loadClass(uninstalledBundle(), "com.example.Foo"));
  }

  /** Mimics the OSGi contract: an uninstalled bundle throws on getEntry and adapt. */
  private static Bundle uninstalledBundle() {
    Bundle bundle = mock(Bundle.class);
    IllegalStateException uninstalled = new IllegalStateException("Bundle has been uninstalled");
    when(bundle.getEntry(anyString())).thenThrow(uninstalled);
    when(bundle.adapt(BundleWiring.class)).thenThrow(uninstalled);
    return bundle;
  }
}
