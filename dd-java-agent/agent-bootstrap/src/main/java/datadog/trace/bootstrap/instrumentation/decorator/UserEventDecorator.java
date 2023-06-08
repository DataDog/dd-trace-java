package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;

public class UserEventDecorator extends EventDecorator {

  public void onLoginSuccess(String userId, Map<String, String> metadata) {
    System.out.println("LoginSuccess("+userId+")");
    TraceSegment segment = AgentTracer.get().getTraceSegment();
    if (userId != null) {
      segment.setTagTop("usr.id", userId);
    }
    onEvent(segment, "users.login.success", metadata);
  }

  public void onLoginFailure(String userId, Map<String, String> metadata, boolean userExists) {
    System.out.println("LoginFailure("+userId+", "+userExists+")");
    TraceSegment segment = AgentTracer.get().getTraceSegment();
    if (userId != null) {
      segment.setTagTop("appsec.events.users.login.failure.usr.id", userId);
    }
    segment.setTagTop("appsec.events.users.login.failure.usr.exists", userExists);
    onEvent(segment, "users.login.failure", metadata);
  }

  public void onSignup(String userId, Map<String, String> metadata) {
    System.out.println("Signup("+userId+")");
    TraceSegment segment = AgentTracer.get().getTraceSegment();
    if (userId != null) {
      segment.setTagTop("usr.id", userId);
    }
    onEvent(segment, "users.signup", metadata);
  }
}
