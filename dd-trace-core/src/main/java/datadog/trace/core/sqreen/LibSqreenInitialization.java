package datadog.trace.core.sqreen;

import io.sqreen.powerwaf.Powerwaf;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

@Slf4j
public class LibSqreenInitialization {
    public static final boolean ONLINE = initPowerWAF();

    private static boolean initPowerWAF() {
        try {
            boolean simpleLoad = System.getProperty("POWERWAF_SIMPLE_LOAD") != null;
            Powerwaf.initialize(simpleLoad);
        } catch (Exception e) {
            log.error("Error initializing libsqreen. " +
                            "In-app WAF and detailed metrics will not be available", e);
            return false;
        }

        return true;
    }
}
