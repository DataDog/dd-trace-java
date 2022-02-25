package datadog.trace.api;

import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.context.ScopeListener;

/** A class with Datadog tracer features. */
public interface Tracer {

  /** Get the trace id of the active trace. Returns 0 if there is no active trace. */
  String getTraceId();

  /**
   * Get the span id of the active span of the active trace. Returns 0 if there is no active trace.
   */
  String getSpanId();

  /**
   * Add a new interceptor to the tracer. Interceptors with duplicate priority to existing ones are
   * ignored.
   *
   * @param traceInterceptor
   * @return false if an interceptor with same priority exists.
   */
  boolean addTraceInterceptor(TraceInterceptor traceInterceptor);

  /**
   * Attach a scope listener to the global scope manager
   *
   * @param listener listener to attach
   */
  void addScopeListener(ScopeListener listener);

  /**
   * Add tags identifying the user associated with the request.
   *
   * <p>Only an arbitrary (but unique) user id is required. The remaining data can be set by calling
   * the <code>withXXX</code> methods on the returned {@link UserDetails} object.
   *
   * <p>The information is transmitted in tags of the local root span. All the tags are prefixed by
   * <code>usr.</code> (e.g. <code>usr.id</code>).
   *
   * <p>This is a noop in case there is no active trace.
   *
   * @param userId an arbitrary string identifying the user, or null/empty string to remove the tag
   *     <code>usr.id</code>
   * @return an object that allows setting further tags.
   */
  UserDetails addUserDetails(String userId);

  /** Object used to set <code>usr.*</code> tags in the local root span. */
  interface UserDetails {
    String ID_TAG = "usr.id";
    String NAME_TAG = "usr.name";
    String EMAIL_TAG = "usr.email";
    String SESSION_ID_TAG = "usr.session_id";
    String ROLE_TAG = "usr.role";

    UserDetails withEmail(String email);

    UserDetails withName(String name);

    UserDetails withSessionId(String sessionId);

    UserDetails withRole(String role);

    /**
     * Sets a tag in the local root span to associate arbitrary data with the user.
     *
     * @param tagSuffix the suffix of the tag, after <code>usr.</code>
     * @param value the value of the tag. If null, the call is a noop
     * @return this object, for setting further tags
     */
    UserDetails withCustomData(String tagSuffix, String value);
  }

  class NoopUserDetails implements UserDetails {
    public static final UserDetails INSTANCE = new NoopUserDetails();

    private NoopUserDetails() {}

    @Override
    public UserDetails withEmail(String email) {
      return this;
    }

    @Override
    public UserDetails withName(String name) {
      return this;
    }

    @Override
    public UserDetails withSessionId(String sessionId) {
      return this;
    }

    @Override
    public UserDetails withRole(String role) {
      return this;
    }

    @Override
    public UserDetails withCustomData(String tagSuffix, String value) {
      return this;
    }
  }
}
