package datadog.trace.instrumentation.servlet3;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.instrumentation.servlet3.LegacyResponses.oldResponseWithStatus;
import static datadog.trace.instrumentation.servlet3.Servlet3Decorator.DECORATE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.context.Context;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Runs response decoration end to end for a response class without the Servlet 3.0 header
 * accessors, which {@link HttpServletExtractAdapterTest} only covers at the getter.
 */
class Servlet3ResponseHeaderDoneTest extends AbstractInstrumentationTest {
  // Object, because test-class fields can't name bootstrap types such as SubscriptionService
  private Object subscriptions;
  private boolean appSecWasActive;

  @AfterEach
  void resetAppSec() {
    if (subscriptions != null) {
      ((SubscriptionService) subscriptions).reset();
      ActiveSubsystems.APPSEC_ACTIVE = appSecWasActive;
    }
  }

  @Test
  void responseHeaderDoneFiresForResponseWithoutHeaderAccessors() throws Exception {
    List<Integer> statuses = new ArrayList<>();
    List<String> headers = new ArrayList<>();
    AtomicInteger headersDone = new AtomicInteger();
    subscribe(statuses, headers, headersDone);
    // a working getStatus, so decoration gets past the status to the header visitor
    HttpServletResponse response = oldResponseWithStatus("OldResponseWithStatus", 200);

    Context context = DECORATE.startSpan(emptyRequest(), Context.root());
    AgentSpan span = AgentSpan.fromContext(context);
    try {
      DECORATE.onResponse(span, response);
    } finally {
      span.finish();
    }

    assertEquals(Collections.singletonList(200), statuses);
    assertEquals(Collections.emptyList(), headers);
    assertEquals(1, headersDone.get());
  }

  private void subscribe(List<Integer> statuses, List<String> headers, AtomicInteger headersDone) {
    SubscriptionService subscriptions =
        (SubscriptionService) AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC);
    this.subscriptions = subscriptions;
    appSecWasActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
    // anonymous classes, not lambdas: a lambda's synthetic method on the test class would name
    // bootstrap types such as Flow
    subscriptions.registerCallback(
        EVENTS.requestStarted(),
        new Supplier<Flow<Object>>() {
          @Override
          public Flow<Object> get() {
            return new Flow.ResultFlow<>(new Object());
          }
        });
    subscriptions.registerCallback(
        EVENTS.responseStarted(),
        new BiFunction<RequestContext, Integer, Flow<Void>>() {
          @Override
          public Flow<Void> apply(RequestContext ctx, Integer status) {
            statuses.add(status);
            return Flow.ResultFlow.empty();
          }
        });
    subscriptions.registerCallback(
        EVENTS.responseHeader(),
        new TriConsumer<RequestContext, String, String>() {
          @Override
          public void accept(RequestContext ctx, String name, String value) {
            headers.add(name + "=" + value);
          }
        });
    subscriptions.registerCallback(
        EVENTS.responseHeaderDone(),
        new Function<RequestContext, Flow<Void>>() {
          @Override
          public Flow<Void> apply(RequestContext ctx) {
            headersDone.incrementAndGet();
            return Flow.ResultFlow.empty();
          }
        });
  }

  /** A request with no headers or attributes, enough to start a server span. */
  private static HttpServletRequest emptyRequest() {
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            HttpServletRequest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getHeaderNames":
                case "getAttributeNames":
                  return Collections.emptyEnumeration();
                default:
                  return null;
              }
            });
  }
}
