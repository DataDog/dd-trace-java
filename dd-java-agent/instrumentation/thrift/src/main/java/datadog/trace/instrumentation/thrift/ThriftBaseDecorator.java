package datadog.trace.instrumentation.thrift;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;

public abstract class ThriftBaseDecorator extends BaseDecorator {

  public void withMethod(final AgentSpan span, final String methodName) {
    span.setTag(ThriftConstants.Tags.METHOD,methodName);
  }
  public void withResource(final AgentSpan span, final String resourceName) {
    span.setResourceName(resourceName);
  }

  public void withArgs(final AgentSpan span, final String methodName,TBase tb) {
    if (tb!=null) {
      span.setTag(ThriftConstants.Tags.ARGS, getArguments(methodName, tb));
    }
  }

  public String getArguments(String method, TBase base) {
    int idx = 0;
    StringBuilder buffer = new StringBuilder(method).append("(");
    while (true) {
      TFieldIdEnum field = base.fieldForId(++idx);
      if (field == null) {
        idx--;
        break;
      }
      buffer.append(field.getFieldName()).append(", ");
    }
    if (idx > 0) {
      buffer.delete(buffer.length() - 2, buffer.length());
    }
    return buffer.append(")").toString();
  }

  public abstract CharSequence spanName();
}
