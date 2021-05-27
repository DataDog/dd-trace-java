package com.datadog.appsec;

public interface AppSecInterface {

  void subscribeCallback(Callback callback);
  void unsubscribeCallback(Callback callback);

}
