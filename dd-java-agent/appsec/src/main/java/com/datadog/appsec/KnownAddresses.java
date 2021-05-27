package com.datadog.appsec;

import java.util.Map;

public interface KnownAddresses {
  
  Address<String> REQUEST_URI_RAW = new Address<>("server.request.uri.raw");
  Address<Map<String, String>> REQUEST_HEADERS = new Address<>("server.request.headers");

}
