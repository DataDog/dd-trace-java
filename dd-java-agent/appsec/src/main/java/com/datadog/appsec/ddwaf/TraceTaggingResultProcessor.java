package com.datadog.appsec.ddwaf;

import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import com.datadog.appsec.gateway.RateLimiter;
import com.datadog.appsec.report.AppSecEvent;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.Config;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.stacktrace.StackTraceEvent;
import datadog.trace.util.stacktrace.StackTraceFrame;
import datadog.trace.util.stacktrace.StackUtils;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processor for handling trace tagging results from the WAF. This class processes the new trace
 * tagging result structure that includes keep flags, attributes, and event generation control.
 */
public class TraceTaggingResultProcessor {
  private static final Logger log = LoggerFactory.getLogger(TraceTaggingResultProcessor.class);
  private static final String EXPLOIT_DETECTED_MSG = "Exploit detected";

  private final RateLimiter rateLimiter;

  public TraceTaggingResultProcessor(RateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  /**
   * Process a trace tagging result from the WAF.
   *
   * @param result The trace tagging result to process
   * @param flow The changeable flow for request modification
   * @param reqCtx The request context
   * @param gwCtx The gateway context
   */
  public void processResult(
      WAFResultData.TraceTaggingResult result,
      ChangeableFlow flow,
      AppSecRequestContext reqCtx,
      GatewayContext gwCtx) {

    // Handle timeout
    if (result.timeout) {
      if (gwCtx.isRasp) {
        reqCtx.increaseRaspTimeouts();
        WafMetricCollector.get().raspTimeout(gwCtx.raspRuleType);
      } else {
        reqCtx.increaseWafTimeouts();
        log.debug("Timeout calling the WAF");
      }
      return;
    }

    // Handle keep flag for sampling priority
    if (result.keep) {
      handleKeepFlag(reqCtx);
    }

    // Handle actions (blocking, redirects, stack generation, trace tagging)
    if (result.actions != null && !result.actions.isEmpty()) {
      handleActions(result.actions, flow, reqCtx, gwCtx);
    }

    // Handle events
    if (result.events != null && !result.events.isEmpty()) {
      handleEvents(result.events, reqCtx, gwCtx);
    }

    // Handle attributes for trace tagging
    if (result.attributes != null && !result.attributes.isEmpty()) {
      handleAttributes(result.attributes, reqCtx);
    }

    // Set blocking state if flow is blocking
    if (flow.isBlocking()) {
      if (gwCtx.isRasp) {
        reqCtx.setRaspBlocked();
      } else {
        reqCtx.setWafBlocked();
      }
    }
  }

  /** Handle the keep flag by setting appropriate span tags for sampling priority. */
  private void handleKeepFlag(AppSecRequestContext reqCtx) {
    AgentSpan activeSpan = AgentTracer.get().activeSpan();
    if (activeSpan != null) {
      // Keep event related span, because it could be ignored in case of
      // reduced datadog sampling rate.
      activeSpan.getLocalRootSpan().setTag(Tags.ASM_KEEP, true);
      // If APM is disabled, inform downstream services that the current
      // distributed trace contains at least one ASM event and must inherit
      // the given force-keep priority
      activeSpan.getLocalRootSpan().setTag(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
    } else {
      // If active span is not available the ASM_KEEP tag will be set in the GatewayBridge
      // when the request ends
      log.debug("There is no active span available");
    }
  }

  /** Handle actions from the WAF result. */
  private void handleActions(
      Map<String, Map<String, Object>> actions,
      ChangeableFlow flow,
      AppSecRequestContext reqCtx,
      GatewayContext gwCtx) {

    for (Map.Entry<String, Map<String, Object>> action : actions.entrySet()) {
      String actionType = action.getKey();
      Map<String, Object> actionParams = action.getValue();

      if ("trace_tagging".equals(actionType)) {
        handleTraceTaggingAction(actionParams, reqCtx);
      } else {
        WAFModule.ActionInfo actionInfo = new WAFModule.ActionInfo(actionType, actionParams);

        if ("block_request".equals(actionInfo.type)) {
          Flow.Action.RequestBlockingAction rba =
              createBlockRequestAction(actionInfo, reqCtx, gwCtx.isRasp);
          flow.setAction(rba);
        } else if ("redirect_request".equals(actionInfo.type)) {
          Flow.Action.RequestBlockingAction rba =
              createRedirectRequestAction(actionInfo, reqCtx, gwCtx.isRasp);
          flow.setAction(rba);
        } else if ("generate_stack".equals(actionInfo.type)) {
          if (Config.get().isAppSecStackTraceEnabled()) {
            String stackId = (String) actionInfo.parameters.get("stack_id");
            StackTraceEvent stackTraceEvent = createExploitStackTraceEvent(stackId);
            reqCtx.reportStackTrace(stackTraceEvent);
          } else {
            log.debug("Ignoring action with type generate_stack (stack traces disabled)");
          }
        } else {
          log.info("Ignoring action with type {}", actionInfo.type);
          if (!gwCtx.isRasp) {
            reqCtx.setWafRequestBlockFailure();
          }
        }
      }
    }
  }

  /** Handle trace tagging action. */
  private void handleTraceTaggingAction(
      Map<String, Object> actionParams, AppSecRequestContext reqCtx) {
    // Handle keep flag
    Object keepObj = actionParams.get("keep");
    if (keepObj instanceof Boolean && (Boolean) keepObj) {
      handleKeepFlag(reqCtx);
    }

    // Handle attributes
    Object attrsObj = actionParams.get("attributes");
    if (attrsObj instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> attributes = (Map<String, Object>) attrsObj;
      if (!attributes.isEmpty()) {
        handleAttributes(attributes, reqCtx);
      }
    }
  }

  /** Handle events from the WAF result. */
  private void handleEvents(
      Collection<WAFResultData> events, AppSecRequestContext reqCtx, GatewayContext gwCtx) {

    if (!events.isEmpty()) {
      if (!reqCtx.isThrottled(rateLimiter)) {
        AgentSpan activeSpan = AgentTracer.get().activeSpan();
        if (activeSpan != null) {
          log.debug("Setting force-keep tag on the current span");
          // Keep event related span, because it could be ignored in case of
          // reduced datadog sampling rate.
          activeSpan.getLocalRootSpan().setTag(Tags.ASM_KEEP, true);
          // If APM is disabled, inform downstream services that the current
          // distributed trace contains at least one ASM event and must inherit
          // the given force-keep priority
          activeSpan
              .getLocalRootSpan()
              .setTag(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
        } else {
          // If active span is not available the ASM_KEEP tag will be set in the GatewayBridge
          // when the request ends
          log.debug("There is no active span available");
        }

        // Convert WAFResultData to AppSecEvent
        Collection<AppSecEvent> appSecEvents = convertToAppSecEvents(events);
        reqCtx.reportEvents(appSecEvents);
      } else {
        log.debug("Rate limited WAF events");
        if (!gwCtx.isRasp) {
          reqCtx.setWafRateLimited();
        }
      }
    }
  }

  /** Handle attributes for trace tagging. */
  private void handleAttributes(Map<String, Object> attributes, AppSecRequestContext reqCtx) {
    // Serialize attributes to the trace segment
    // This will be handled by the trace segment post processor
    reqCtx.setTraceAttributes(attributes);
  }

  /** Convert WAFResultData collection to AppSecEvent collection. */
  private Collection<AppSecEvent> convertToAppSecEvents(Collection<WAFResultData> wafResults) {
    return wafResults.stream()
        .map(this::buildEvent)
        .filter(event -> event != null)
        .collect(java.util.stream.Collectors.toList());
  }

  /** Build an AppSecEvent from WAFResultData. */
  private AppSecEvent buildEvent(WAFResultData wafResult) {
    if (wafResult == null || wafResult.rule == null || wafResult.rule_matches == null) {
      log.warn("WAF result is empty: {}", wafResult);
      return null;
    }

    Long spanId = null;
    AgentSpan agentSpan = AgentTracer.get().activeSpan();
    if (agentSpan != null) {
      spanId = agentSpan.getSpanId();
    }

    return new AppSecEvent.Builder()
        .withRule(wafResult.rule)
        .withRuleMatches(wafResult.rule_matches)
        .withSpanId(spanId)
        .withStackId(wafResult.stack_id)
        .build();
  }

  /** Create a block request action. */
  private Flow.Action.RequestBlockingAction createBlockRequestAction(
      WAFModule.ActionInfo actionInfo, AppSecRequestContext reqCtx, boolean isRasp) {

    Integer statusCode = (Integer) actionInfo.parameters.get("status_code");
    if (statusCode == null) {
      statusCode = 403;
    }

    String type = (String) actionInfo.parameters.get("type");
    BlockingContentType blockingContentType = BlockingContentType.AUTO;
    if ("json".equals(type)) {
      blockingContentType = BlockingContentType.JSON;
    } else if ("html".equals(type)) {
      blockingContentType = BlockingContentType.HTML;
    }

    if (!isRasp) {
      reqCtx.setWafBlocked();
    }

    return new Flow.Action.RequestBlockingAction(statusCode, blockingContentType);
  }

  /** Create a redirect request action. */
  private Flow.Action.RequestBlockingAction createRedirectRequestAction(
      WAFModule.ActionInfo actionInfo, AppSecRequestContext reqCtx, boolean isRasp) {

    Integer statusCode = (Integer) actionInfo.parameters.get("status_code");
    if (statusCode == null) {
      statusCode = 303;
    }

    String location = (String) actionInfo.parameters.get("location");
    if (location == null) {
      location = "https://example.com/";
    }

    Map<String, String> extraHeaders = Collections.singletonMap("Location", location);

    if (!isRasp) {
      reqCtx.setWafBlocked();
    }

    return new Flow.Action.RequestBlockingAction(
        statusCode, BlockingContentType.AUTO, extraHeaders);
  }

  /** Create an exploit stack trace event. */
  private StackTraceEvent createExploitStackTraceEvent(String stackId) {
    if (stackId == null || stackId.isEmpty()) {
      return null;
    }
    List<StackTraceFrame> result = StackUtils.generateUserCodeStackTrace();
    return new StackTraceEvent(result, "java", stackId, EXPLOIT_DETECTED_MSG);
  }
}
