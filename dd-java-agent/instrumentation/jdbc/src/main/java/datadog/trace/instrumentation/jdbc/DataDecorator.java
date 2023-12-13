package datadog.trace.instrumentation.jdbc;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.function.BiFunction;
import net.sf.jsqlparser.JSQLParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataDecorator {

  public static final DataDecorator DECORATE = new DataDecorator();
  private static final Logger log = LoggerFactory.getLogger(DataDecorator.class);

  public ResultSet onResultSet(ResultSet rs) throws SQLException {
    return new ResultSetReadWrapper(
        rs,
        resultStructure -> {
          CallbackProvider cbpAppsec =
              AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
          if (cbpAppsec == null) {
            return;
          }

          AgentSpan span = AgentTracer.activeSpan();
          if (span == null) {
            return;
          }

          RequestContext requestContext = span.getRequestContext();
          if (requestContext == null) {
            return;
          }

          BiFunction<RequestContext, Map<String, Map<String, Object>>, Flow<Void>> callback =
              cbpAppsec.getCallback(EVENTS.databaseRead());
          if (callback != null) {
            callback.apply(requestContext, resultStructure);
          }
        });
  }

  public void onUpdateResult(String sql, int result) throws JSQLParserException {
    if (result > 0) {
      Map<String, Map<String, Object>> resultStructure = SqlDataExtractor.extractData(sql);

      CallbackProvider cbpAppsec = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      if (cbpAppsec == null) {
        return;
      }

      AgentSpan span = AgentTracer.activeSpan();
      if (span == null) {
        return;
      }

      RequestContext requestContext = span.getRequestContext();
      if (requestContext == null) {
        return;
      }

      BiFunction<RequestContext, Map<String, Map<String, Object>>, Flow<Void>> callback =
          cbpAppsec.getCallback(EVENTS.databaseWrite());
      if (callback != null) {
        callback.apply(requestContext, resultStructure);
      }
    }
  }

  public static void logSQLException(Exception ex) {
    if (log.isDebugEnabled()) {
      log.debug("Database Data capturing error", ex);
    }
  }
}
