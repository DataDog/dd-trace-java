package datadog.trace.instrumentation.tomcat;

import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;

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

  /**
   * Commits a blocking response through the {@link BlockResponseFunction} registered on the given
   * request context and, if the commit fails, reports the failure via {@link
   * AppSecContext#reportBlockFailure()}.
   *
   * <p>This is the single choke point shared by all Tomcat blocking call sites so the {@code
   * block_failure} telemetry is not duplicated inline.
   *
   * <p>Exceptions thrown by the commit attempt are deliberately propagated instead of being
   * converted into a {@code false} return: the calling advice methods declare {@code suppress =
   * Throwable.class} and rely on the whole advice being aborted so that their blocking success-path
   * side effects (closing the connection, injecting a {@link
   * datadog.appsec.api.blocking.BlockingException}, marking the trace segment effectively blocked)
   * are skipped when no response was committed. Such exception-based commit failures are therefore
   * not reported to the {@code block_failure} telemetry, which is the same known gap as the Netty
   * implementation this mirrors.
   *
   * @return {@code true} if the blocking response was committed, {@code false} otherwise (including
   *     when no {@link BlockResponseFunction} is registered, in which case nothing was attempted
   *     and no failure is reported).
   */
  public static boolean tryCommitAndReport(
      RequestContext reqCtx, Flow.Action.RequestBlockingAction rba) {
    BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
    if (brf == null) {
      // nothing was attempted, so this is not a block failure
      return false;
    }
    if (brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba)) {
      return true;
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
