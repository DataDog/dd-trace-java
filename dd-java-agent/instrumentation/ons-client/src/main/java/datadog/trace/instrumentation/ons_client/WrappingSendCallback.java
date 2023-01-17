package datadog.trace.instrumentation.ons_client;

import com.aliyun.openservices.ons.api.OnExceptionContext;
import com.aliyun.openservices.ons.api.SendCallback;
import com.aliyun.openservices.ons.api.SendResult;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;

public class WrappingSendCallback implements SendCallback {
  private final SendCallback callback;
  private final AgentScope scope;

  public WrappingSendCallback(SendCallback callback, AgentScope scope) {
    this.callback = callback;
    this.scope = scope;
  }

  @Override
  public void onSuccess(SendResult sendResult) {
    scope.span().setTag("sendAsync_status","success");
    scope.close();
    scope.span().finish();
    callback.onSuccess(sendResult);
  }

  @Override
  public void onException(OnExceptionContext context) {
    scope.span().setTag("sendAsync_status","exception");
    scope.span().addThrowable(context.getException());
    scope.close();
    scope.span().finish();
    callback.onException(context);
  }
}
