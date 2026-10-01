package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.HelperScanner.isHelperClass;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HelperScannerTest {
    private static final String SHADED_CLASS = "datadog.trace.agent.tooling.shaded.somelib.Client";

    private static final InstrumenterModule DEFAULT_MODULE = new InstrumenterModule("helper-scanner-test") {};

    /** Mirrors a real module claiming third-party code shaded into its own package. */
    private static final InstrumenterModule CLAIMING_MODULE = new InstrumenterModule("helper-scanner-test-claiming") {
        @Override
        public boolean isHelperClass(String className) {
            return className.startsWith(packageName + ".shaded.");
        }
    };

    @Test
    void neverTreatsBootstrapClassesAsHelpers() {
        assertFalse(isHelperClass("java.util.ArrayList", true, CLAIMING_MODULE));
        assertFalse(isHelperClass("org.slf4j.Logger", true, CLAIMING_MODULE));
    }

    @Test
    void treatsModuleOutputClassesAsHelpers() {
        assertTrue(isHelperClass("some.instrumentation.Advice", true, DEFAULT_MODULE));
    }

    @Test
    void treatsSharedHelperPackagesAsHelpers() {
        assertTrue(isHelperClass("datadog.trace.agent.tooling.iast.Sink", false, DEFAULT_MODULE));
    }

    @Test
    void defersToModuleWhenNotOtherwiseRecognized() {
        assertFalse(isHelperClass(SHADED_CLASS, false, DEFAULT_MODULE));
        assertTrue(isHelperClass(SHADED_CLASS, false, CLAIMING_MODULE));
    }

    @Test
    void defaultModuleImplementationClaimsNoHelpers() {
        assertFalse(DEFAULT_MODULE.isHelperClass(SHADED_CLASS));
    }
}
