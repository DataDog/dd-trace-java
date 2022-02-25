package datadog.trace.api.interceptor;

import datadog.trace.api.Tracer;

public final class MutableSpanUserDetails implements Tracer.UserDetails {
  private final MutableSpan rootSpan;

  public MutableSpanUserDetails(MutableSpan agentSpan) {
    rootSpan = agentSpan;
  }

  @Override
  public Tracer.UserDetails withEmail(String email) {
    rootSpan.setTag(EMAIL_TAG, email);
    return this;
  }

  @Override
  public Tracer.UserDetails withName(String name) {
    rootSpan.setTag(NAME_TAG, name);
    return this;
  }

  @Override
  public Tracer.UserDetails withSessionId(String sessionId) {
    rootSpan.setTag(SESSION_ID_TAG, sessionId);
    return this;
  }

  @Override
  public Tracer.UserDetails withRole(String role) {
    rootSpan.setTag(ROLE_TAG, role);
    return this;
  }

  @Override
  public Tracer.UserDetails withCustomData(String tagSuffix, String value) {
    String tag = "usr." + tagSuffix;
    rootSpan.setTag(tag, value);
    return this;
  }
}
