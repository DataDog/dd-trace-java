package datadog.trace.instrumentation.tomcat7;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import java.io.OutputStream;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class GlassFishBlockingHelper {

  public static boolean commitBlocking(
      HttpServletRequest request,
      HttpServletResponse response,
      Flow.Action.RequestBlockingAction rba) {
    if (response == null) {
      return false;
    }
    try {
      if (response.isCommitted()) {
        return false;
      }
      response.reset();
      response.setStatus(BlockingActionHelper.getHttpCode(rba.getStatusCode()));
      for (Map.Entry<String, String> e : rba.getExtraHeaders().entrySet()) {
        response.setHeader(e.getKey(), e.getValue());
      }
      if (rba.getBlockingContentType() != BlockingContentType.NONE) {
        String accept = request != null ? request.getHeader("Accept") : null;
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(rba.getBlockingContentType(), accept);
        byte[] body = BlockingActionHelper.getTemplate(type, rba.getSecurityResponseId());
        if (body != null) {
          response.setHeader("Content-Type", BlockingActionHelper.getContentType(type));
          response.setHeader("Content-Length", Integer.toString(body.length));
          OutputStream os = response.getOutputStream();
          os.write(body);
          os.close();
        }
      }
      response.flushBuffer();
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
