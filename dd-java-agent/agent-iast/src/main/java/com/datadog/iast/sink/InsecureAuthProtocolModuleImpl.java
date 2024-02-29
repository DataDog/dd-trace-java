package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.InsecureAuthProtocolModule;
import java.util.Map;
import javax.annotation.Nullable;

public class InsecureAuthProtocolModuleImpl extends SinkModuleBase
    implements InsecureAuthProtocolModule {

  private static final String BASIC = "Basic";
  private static final String DIGEST = "Digest";

  public InsecureAuthProtocolModuleImpl(Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onRequestEnd(IastContext ctx, IGSpanInfo igSpanInfo) {
    if (!(ctx instanceof IastRequestContext)) {
      return;
    }
    final IastRequestContext iastRequestContext = (IastRequestContext) ctx;
    String authorization = iastRequestContext.getAuthorization();
    if (authorization == null) {
      return;
    }
    Map<String, Object> tags = igSpanInfo.getTags();
    if (isIgnorableResponseCode((Integer) tags.get("http.status_code"))) {
      return;
    }
    String authScheme =
        authorization.startsWith(BASIC) ? BASIC : authorization.startsWith(DIGEST) ? DIGEST : null;
    if (authScheme == null) {
      return;
    }
    final Evidence result = new Evidence(String.format("Authorization : %s", authScheme));
    report(VulnerabilityType.INSECURE_AUTH_PROTOCOL, result);
  }

  @Override
  public boolean isIgnorableResponseCode(@Nullable Integer httpStatus) {
    // To minimize false positives when we get auth credentials to a page that doesn't exist (e.g.
    // happens with vulnerability scanners),
    // we'll just ignore this vulnerability when there is no success response.
    return httpStatus != null && httpStatus >= 400;
  }
}
