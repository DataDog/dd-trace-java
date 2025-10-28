package datadog.trace.instrumentation.jetty_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.ByteBuffer;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;

public class CallbackWrapper implements Response.Listener, Request.Listener {

  private final AgentSpan parent;
  private final AgentSpan span;
  private final Object delegate;

  public CallbackWrapper(AgentSpan parent, AgentSpan span, Object delegate) {
    this.parent = parent;
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
      // this probably does the wrong thing, but preserves old behaviour and is consistent
      // with other http clients with completion callback registration
      try (AgentScope scope = activate(parent)) {
        ((Response.CompleteListener) delegate).onComplete(result);
      }
    }
  }

  @Override
  public void onContent(Response response, ByteBuffer content) {
    if (delegate instanceof Response.ContentListener) {
      try (AgentScope scope = activate(span)) {
        ((Response.ContentListener) delegate).onContent(response, content);
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
  public boolean onHeader(Response response, HttpField field) {
    if (delegate instanceof Response.HeaderListener) {
      try (AgentScope scope = activate(span)) {
        return ((Response.HeaderListener) delegate).onHeader(response, field);
      }
    }
    return false;
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
  public void onContent(Request request, ByteBuffer content) {
    if (delegate instanceof Request.ContentListener) {
      try (AgentScope scope = activate(span)) {
        ((Request.ContentListener) delegate).onContent(request, content);
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
