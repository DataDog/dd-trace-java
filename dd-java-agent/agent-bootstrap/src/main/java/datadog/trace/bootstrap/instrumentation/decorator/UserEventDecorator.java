package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import javax.annotation.Nonnull;

public class UserEventDecorator extends EventDecorator {

  public static final UserEventDecorator DECORATE = new UserEventDecorator();

  public void onLoginSuccess(String userId, Map<String, String> metadata) {
    TraceSegment segment = AgentTracer.get().getTraceSegment();
    if (userId != null) {
      segment.setTagTop("usr.id", userId);
    }
    onEvent(segment, "users.login.success", metadata);
  }

  public void onLoginFailure(String userId, Map<String, String> metadata, boolean userExists) {
    TraceSegment segment = AgentTracer.get().getTraceSegment();
    if (userId != null) {
      segment.setTagTop("appsec.events.users.login.failure.usr.id", userId);
    }
    segment.setTagTop("appsec.events.users.login.failure.usr.exists", userExists);
    onEvent(segment, "users.login.failure", metadata);
  }

  public void onSignup(String userId, @Nonnull Map<String, String> metadata) {
    TraceSegment segment = AgentTracer.get().getTraceSegment();
    if (userId != null) {
      segment.setTagTop("usr.id", userId);
    }
    onEvent(segment, "users.signup", metadata);
  }
}
