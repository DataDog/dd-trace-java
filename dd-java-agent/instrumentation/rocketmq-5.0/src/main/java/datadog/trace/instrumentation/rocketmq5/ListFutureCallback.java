package datadog.trace.instrumentation.rocketmq5;

import org.apache.rocketmq.shaded.com.google.common.util.concurrent.FutureCallback;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.Futures;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.MoreExecutors;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.SettableFuture;

import java.util.ArrayList;
import java.util.List;
public  class ListFutureCallback<T> implements FutureCallback<List<T>> {
    private final List<SettableFuture<T>> futures;

    public ListFutureCallback(List<SettableFuture<T>> futures) {
      this.futures = futures;
    }

    @Override
    public void onSuccess(List<T> result) {
      for (int i = 0; i < result.size(); i++) {
        futures.get(i).set(result.get(i));
      }
    }

    @Override
    public void onFailure(Throwable t) {
      for (SettableFuture<T> future : futures) {
        future.setException(t);
      }
    }
}

