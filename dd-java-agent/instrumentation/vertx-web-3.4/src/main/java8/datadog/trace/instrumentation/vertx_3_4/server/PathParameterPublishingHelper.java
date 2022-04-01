package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.ext.web.impl.Utils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

public class PathParameterPublishingHelper {
  public static void publishParams(Matcher m, List<String> groups) {
    AgentSpan agentSpan = activeSpan();
    if (agentSpan == null) {
      return;
    }

    CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
    BiFunction<RequestContext<Object>, Map<String, ?>, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestPathParams());
    RequestContext<Object> requestContext = agentSpan.getRequestContext();
    if (requestContext == null || callback == null) {
      return;
    }

    final int groupCount = m.groupCount();
    final Map<String, String> params = new HashMap<>(groupCount + (groupCount + 2) / 3);
    if (groups != null) {
      for (int i = 0; i < groups.size(); i++) {
        final String k = groups.get(i);
        final String value = Utils.urlDecode(m.group("p" + i), false);
        params.put(k, value);
      }
    } else {
      for (int i = 0; i < groupCount; i++) {
        String group = m.group(i + 1);
        if (group != null) {
          final String k = "param" + i;
          final String value = Utils.urlDecode(group, false);
          params.put(k, value);
        }
      }
    }

    callback.apply(requestContext, params);
  }
}
