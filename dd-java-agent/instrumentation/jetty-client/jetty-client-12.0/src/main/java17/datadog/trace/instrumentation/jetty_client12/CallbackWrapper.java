package datadog.trace.instrumentation.jetty_client12;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;

public class CallbackWrapper implements Response.Listener, Request.Listener {

  private final AgentSpan parent;
  private final AgentSpan span;
  private final Object delegate;

  public CallbackWrapper(AgentSpan parent, AgentSpan span, Object delegate) {
    this.parent = parent != null ? parent : noopSpan();
    this.span = span;
    this.delegate = delegate;
  }

  @Override
  public void onBegin(Response response) {
    if (delegate instanceof Response.BeginListener) {
      try (AgentScope scope = activate(span)) {
        ((Response.BeginListener) delegate).onBegin(response);
      }
    }
  }

  @Override
  public void onComplete(Result result) {
    if (delegate instanceof Response.CompleteListener) {
      try (AgentScope scope = activate(parent)) {
        ((Response.CompleteListener) delegate).onComplete(result);
      }
    }
  }

  @Override
  public void onFailure(Response response, Throwable failure) {
    if (delegate instanceof Response.FailureListener) {
      try (AgentScope scope = activate(span)) {
        ((Response.FailureListener) delegate).onFailure(response, failure);
      }
    }
  }

  @Override
  public void onHeaders(Response response) {
    if (delegate instanceof Response.HeadersListener) {
      try (AgentScope scope = activate(span)) {
        ((Response.HeadersListener) delegate).onHeaders(response);
      }
    }
  }

  @Override
  public void onSuccess(Response response) {
    if (delegate instanceof Response.SuccessListener) {
      try (AgentScope scope = activate(span)) {
        ((Response.SuccessListener) delegate).onSuccess(response);
      }
    }
  }

  @Override
  public void onBegin(Request request) {
    if (delegate instanceof Request.BeginListener) {
      try (AgentScope scope = activate(span)) {
        ((Request.SuccessListener) delegate).onSuccess(request);
      }
    }
  }

  @Override
  public void onCommit(Request request) {
    if (delegate instanceof Request.CommitListener) {
      try (AgentScope scope = activate(span)) {
        ((Request.CommitListener) delegate).onCommit(request);
      }
    }
  }

  @Override
  public void onFailure(Request request, Throwable failure) {
    if (delegate instanceof Request.FailureListener) {
      try (AgentScope scope = activate(span)) {
        ((Request.FailureListener) delegate).onFailure(request, failure);
      }
    }
  }

  @Override
  public void onHeaders(Request request) {
    if (delegate instanceof Request.HeadersListener) {
      try (AgentScope scope = activate(span)) {
        ((Request.HeadersListener) delegate).onHeaders(request);
      }
    }
  }

  @Override
  public void onQueued(Request request) {
    if (delegate instanceof Request.QueuedListener) {
      try (AgentScope scope = activate(span)) {
        ((Request.QueuedListener) delegate).onQueued(request);
      }
    }
  }

  @Override
  public void onSuccess(Request request) {
    if (delegate instanceof Request.SuccessListener) {
      try (AgentScope scope = activate(span)) {
        ((Request.SuccessListener) delegate).onSuccess(request);
      }
    }
  }

  private AgentScope activate(AgentSpan span) {
    return null == span ? null : activateSpan(span);
  }
}
