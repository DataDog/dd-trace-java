package com.datadog.appsec.event;

import com.datadog.appsec.AppSecRequestContext;
import com.datadog.appsec.event.data.DataBundle;

public interface DataListener extends OrderedCallback {
  void onDataAvailable(ChangeableFlow flow, AppSecRequestContext context, DataBundle dataBundle);
}
