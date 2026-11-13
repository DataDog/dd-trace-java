package datadog.trace.instrumentation.sapjco;

import com.sap.conn.jco.ext.Environment;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers Datadog's passport managers with JCo exactly once. Triggered lazily from the RFC advice
 * the first time an application makes (client) or serves (server) a call.
 *
 * <p>Sending the SAP passport is on by default (it is what joins SAP into the trace and forces
 * SAP-side end-to-end tracing). It can be turned off with {@code
 * dd.trace.sap-jco.passport.enabled=false} ({@code DD_TRACE_SAP_JCO_PASSPORT_ENABLED=false}) to
 * keep the RFC spans without altering the outbound call or making SAP trace.
 *
 * <p>If the application has already registered its own passport manager we leave it in place rather
 * than clobber it. The {@link AtomicBoolean} guards live on this helper class, which is injected
 * into JCo's classloader, so their scope matches JCo's global {@code Environment}.
 */
public final class PassportRegistry {

  private static final Logger log = LoggerFactory.getLogger(PassportRegistry.class);

  private static final boolean PASSPORT_ENABLED =
      ConfigProvider.getInstance()
          .isEnabled(Collections.singletonList("sap-jco"), "trace.", ".passport.enabled", true);

  private static final AtomicBoolean CLIENT_REGISTERED = new AtomicBoolean();
  private static final AtomicBoolean SERVER_REGISTERED = new AtomicBoolean();

  public static void ensureClientRegistered() {
    if (!PASSPORT_ENABLED || !CLIENT_REGISTERED.compareAndSet(false, true)) {
      return;
    }
    if (Environment.isClientPassportManagerRegistered()) {
      log.debug("ClientPassportManager already registered by the application; leaving it in place");
    } else {
      Environment.registerClientPassportManager(new DatadogClientPassportManager());
      log.debug("registered DatadogClientPassportManager");
    }
  }

  public static void ensureServerRegistered() {
    if (!PASSPORT_ENABLED || !SERVER_REGISTERED.compareAndSet(false, true)) {
      return;
    }
    if (Environment.isServerPassportManagerRegistered()) {
      log.debug("ServerPassportManager already registered by the application; leaving it in place");
    } else {
      Environment.registerServerPassportManager(new DatadogServerPassportManager());
      log.debug("registered DatadogServerPassportManager");
    }
  }

  private PassportRegistry() {}
}
