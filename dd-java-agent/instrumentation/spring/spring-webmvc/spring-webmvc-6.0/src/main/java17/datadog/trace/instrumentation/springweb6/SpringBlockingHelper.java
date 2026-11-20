package datadog.trace.instrumentation.springweb6;

import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpringBlockingHelper {
  private static final Logger log = LoggerFactory.getLogger(SpringBlockingHelper.class);

  /**
   * Wraps {@link BlockResponseFunction#tryCommitBlockingResponse(RequestContext,
   * Flow.Action.RequestBlockingAction)} so that an exception thrown by the commit attempt itself
   * (rather than a plain {@code false} return) is still reported as a block failure. The advice
   * that calls this method runs with {@code suppress = Throwable.class}, so without this guard such
   * an exception would propagate out of the advice and be silently swallowed, and the
   * default-method reporting inside {@code tryCommitBlockingResponse} would never run.
   */
  public static boolean tryCommitBlockingResponse(
      BlockResponseFunction blockResponseFunction,
      RequestContext reqCtx,
      Flow.Action.RequestBlockingAction rba) {
    try {
      return blockResponseFunction.tryCommitBlockingResponse(reqCtx, rba);
    } catch (Exception e) {
      log.debug("Error committing blocking response", e);
      Object rawAppSecCtx = reqCtx.getData(RequestContextSlot.APPSEC);
      if (rawAppSecCtx instanceof AppSecContext) {
        ((AppSecContext) rawAppSecCtx).reportBlockFailure();
      }
      return false;
    }
  }
}
