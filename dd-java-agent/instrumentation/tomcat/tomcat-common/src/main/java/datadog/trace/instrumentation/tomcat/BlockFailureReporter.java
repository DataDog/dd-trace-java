package datadog.trace.instrumentation.tomcat;

import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catalina-independent counterpart of {@link TomcatBlockingHelper}: commits a blocking response
 * through the {@link BlockResponseFunction} registered on a request context and reports {@code
 * block_failure} telemetry when the commit fails.
 *
 * <p>Kept separate from {@link TomcatBlockingHelper} (which also carries the Servlet/Catalina
 * fallback commit path) so that instrumentations whose target library version range does not
 * guarantee {@code org.apache.catalina.connector.Request}/{@code Response} presence can list this
 * class in {@code helperClassNames()} without pulling in {@link TomcatBlockingHelper}'s
 * catalina-typed methods and static initializer, which muzzle would otherwise validate against
 * every version in that range.
 */
public class BlockFailureReporter {
  private static final Logger log = LoggerFactory.getLogger(BlockFailureReporter.class);

  /**
   * Commits a blocking response through the {@link BlockResponseFunction} registered on the given
   * request context and, if the commit fails, reports the failure via {@link
   * AppSecContext#reportBlockFailure()}.
   *
   * <p>This is the single choke point shared by all Tomcat blocking call sites so the {@code
   * block_failure} telemetry is not duplicated inline.
   *
   * @return {@code true} if the blocking response was committed, {@code false} otherwise (including
   *     when no {@link BlockResponseFunction} is registered, in which case nothing was attempted
   *     and no failure is reported, or when the commit attempt threw).
   */
  public static boolean tryCommitAndReport(
      RequestContext reqCtx, Flow.Action.RequestBlockingAction rba) {
    BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
    if (brf == null) {
      // nothing was attempted, so this is not a block failure
      return false;
    }
    try {
      if (brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba)) {
        return true;
      }
    } catch (Exception e) {
      log.debug("Error committing blocking response", e);
      reportBlockFailure(reqCtx);
      return false;
    }
    reportBlockFailure(reqCtx);
    return false;
  }

  /** Reports a block failure on the AppSec context bound to the given request context, if any. */
  public static void reportBlockFailure(RequestContext reqCtx) {
    Object rawAppSecCtx = reqCtx.getData(RequestContextSlot.APPSEC);
    if (rawAppSecCtx instanceof AppSecContext) {
      ((AppSecContext) rawAppSecCtx).reportBlockFailure();
    }
  }
}
