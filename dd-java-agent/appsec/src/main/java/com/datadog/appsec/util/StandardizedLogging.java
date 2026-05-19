package com.datadog.appsec.util;

import static com.datadog.appsec.ddwaf.WAFResultData.Rule;

import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.report.AppSecEvent;
import com.datadog.ddwaf.Waf;
import datadog.environment.SystemProperties;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class StandardizedLogging {

  private static final Marker CRITICAL = MarkerFactory.getMarker("CRITICAL");

  public static void appSecStartupError(Logger logger, Throwable t) { // C1
    logger.error(
        CRITICAL,
        "AppSec could not start because of an unexpected error. "
            + "No security activities will be collected. Please contact support at "
            + "https://docs.datadoghq.com/help/ for help.",
        t);
  }

  /*
   * C2: AppSec could not start because the current environment is not supported.
   *    No security activities will be collected. Please contact support at
   *    https://docs.datadoghq.com/help/ for help.
   *    Host information: { operating_system: <OS, e.g. win 32 bits>,
   *    libc: <libc e.g. musl>, arch: <e.g. arm>,
   *    runtime_infos: <arbitrary string about the runtime, e.g. openJdk-vX.Y> }"
   *
   * This cannot be fully implemented inside the appsec module because some failures
   * may be due to incompatible JVM (e.g. running on Java 7).
   */

  // C3
  public static void libddwafCannotBeLoaded(Logger logger, String libc) {
    logger.error(
        CRITICAL,
        "AppSec could not load libddwaf native library, as a result, "
            + "AppSec could not start. No security activities will be collected. "
            + "Please contact support at https://docs.datadoghq.com/help/ for help. "
            + "Host information: operating_system: {}, libc: {}, arch: {}, runtime: {} {}",
        SystemProperties.get("os.name"),
        libc,
        SystemProperties.get("os.arch"),
        SystemProperties.get("java.vm.vendor"),
        SystemProperties.get("java.version"));
  }

  // C4:
  public static void rulesFileNotFound(Logger logger, String filename) {
    logger.error(
        CRITICAL,
        "AppSec could not find the rules file in path {}. "
            + "AppSec will not run any protections in this application. No security activities will be collected.",
        filename);
  }

  public enum RulesInvalidReason {
    INVALID_JSON_FILE {
      @Override
      public String toString() {
        return "invalid JSON file";
      }
    };

    //   ALL_RULES_INVALID {
    //      @Override
    //      public String toString() {
    //        return "all the rules are invalid";
    //      }
    //    }

    public abstract String toString();
  }

  /* C5: Cannot be fully implemented: notice that all rules are invalid needs cooperation from libddwaf */
  public static void rulesFileInvalid(Logger logger, String filename, RulesInvalidReason reason) {
    logger.error(
        CRITICAL,
        "AppSec could not read the rule file {} as it was invalid: {}. "
            + "AppSec will not run any protections in this application.",
        filename,
        reason);
  }

  /*
   * E1: Some rules are invalid in <path_to_rules_file>:
   *     <for (ruleName, invalidReason) in invalid_rules:>
   *       <ruleName>: <invalidReason>"
   *
   * Cannot be implemented without cooperation from libddwaf
   */

  /*
   * D1: Loaded rules:
   *     <for (ruleName,ruleAddresses) in valid_rules:>
   *       <ruleName> on addresses <ruleAddresses>"
   *
   * Cannot be implemented without cooperation from libddwaf
   */

  /*
   * D2: Pushing address <address_name> to the Instrumentation Gateway.
   *
   * Not implemented literally; IG doesn't push addresses in Java.
   */
  public static void addressPushed(Logger logger, Address<?> address) {
    logger.debug("Address {} pushed into request context", address.getKey());
  }

  /*
   * D3: Available addresses <available_addresses> match needs for rules <rules_names>
   *
   * Cannot be implemented without cooperation from libddwaf. At the AppSec level,
   * addresses match listeners, not rules: e.g. the WAF has a whole matches a set
   * of addresses, not individual WAF rules.
   */

  /*
   * D4: Executing AppSec In-App WAF with parameters: <Parameters_passed_to_the_lib>
   *
   * Not implemented: traversing the parameters may have side effects. We could
   * dump the parameters after the conversion, but the conversion happens inside
   * the binding, not on this module.
   */

  // D5
  public static void inAppWafReturn(Logger logger, Waf.ResultWithData resultWithData) {
    logger.debug("AppSec In-App WAF returned: {}", resultWithData);
  }

  // D6, I5
  public static void attackDetected(Logger logger, AppSecEvent event) {
    String ruleId = "unknown rule";
    Rule rule = event.getRule();
    if (rule != null) {
      ruleId = rule.id;
    }

    if (logger.isDebugEnabled()) {
      logger.info("Detecting an attack from rule {}", ruleId);
    }
    logger.debug("Detecting an attack from rule {}: {}", ruleId, event.getRuleMatches());
  }

  /*
   * D7: Rule <rule_name> failed. Error details: ${Error message and stack trace} "
   *
   * Cannot implement: rules are currently only known by libddwaf
   */

  /*
   * D8: AppSec Error. Error details: ${Error message and stack trace}
   *
   * Seems to be a catch-all for errors not covered by other entries.
   * The AppSec module is entered through the following points:
   *   - initialization — covered by other messages
   *   - IG — the instrumentation gateway already catches exceptions that
   *     propagate from its callbacks, which shouldn't happen anyway.
   *   - Attack reporting thread — covered by other messages.
   *   - Fleet management thread (currently unused)
   *
   * Not currently used. It could be used for internal callback errors:
   * events/data event callbacks. But these are reported at higher logging
   * levels, since they ought not to occur. In general, it's difficult to
   * imagine a situation where this should be used rather than a more
   * serious logging entry.
   */

  // E1
  public static void attackReportingFailed(Logger logger, Throwable t) {
    logger.warn("AppSec failed to report AppSec events to the agent.", t);
  }

  /* I1: AppSec starting with the following configuration: {
   *       rules_file_path: <path_to_rule_files>, libddwaf_version: <libbddwaf version>
   *     }
   *
   * Not implemented because configuration and starting are different events.
   * Configuration can happen after starting (through fleet service).
   * The following nonstandard alternative was instead chosen.
   */
  public static void _initialConfigSourceAndLibddwafVersion(Logger logger, String source) {
    if (logger.isDebugEnabled()) {
      logger.info(
          "AppSec initial configuration from {}, libddwaf version: {}", source, Waf.LIB_VERSION);
    }
  }

  // I2
  public static void numLoadedRules(Logger logger, String source, int numRules) {
    if (logger.isDebugEnabled()) {
      logger.info("AppSec loaded {} rules from file {}", numRules, source);
    }
  }

  // I3: high volume; should really not be at info level
  // wilful spec violation: moved to debug level
  public static void executingWAF(Logger logger) {
    logger.debug("Executing AppSec In-App WAF");
  }

  // I4: high volume; should really not be at info level
  // wilful spec violation: moved to debug level
  public static void finishedExecutionWAF(Logger logger, long elapsedMs) {
    logger.debug("Executing AppSec In-App WAF finished. Took {} ms.", elapsedMs);
  }

  // I5 implemented together with D6

  /**
   * I6: Blocking current transaction (rule: <rule_name>)
   *
   * <p>Not implemented: blocking no implemented
   */

  // I7
  public static void attackQueued(Logger logger) {
    // the message is vague or misleading
    logger.info("Pushing new attack to AppSec events");
  }

  // I8
  public static void sendingAttackBatch(Logger logger, int batchSize) {
    logger.info("Sending {} AppSec events to the agent", batchSize);
  }

  /* I9: Dropping <nb_events> AppSec events because <REASON>
   * REASON in (sending batch is full)
   *
   * Not implemented because dropping happens at a level that doesn't know
   * about batches and the number of events they contain.
   * AppSecApi includes a warning about such an event though.
   */

  /* I10: Reporting AppSec event batch because of process shutdown.
   *
   * Cannot currently be implemented. Would require shutdown hooks to flush the
   * attacks. The submission would also have to bypass the usual reporting thread,
   * as it will shutdown upon process shutdown, requiring changes in AppSecApi.
   */
}
