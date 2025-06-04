package datadog.trace.instrumentation;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

public class AllMethodTraceDecorator extends BaseDecorator {
  public static AllMethodTraceDecorator DECORATE = new AllMethodTraceDecorator();
  @Override
  protected String[] instrumentationNames() {
    return new String[]{"method-trace"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return UTF8BytesString.create("method-trace");
  }

  public AgentScope buildSpan(String className,Method method,Object[] args){
    final AgentSpan span = startSpan("method-trace");
    afterStart(span);
    addTags(span,method,args,className);
    return activateSpan(span);
  }

  private void addTags(AgentSpan span,Method method,Object[] args,String className){
    Parameter[] parameters = method.getParameters();
    StringBuffer methodName = new StringBuffer(method.getName());
    methodName.append("(");
    Integer max_size = 1024;
    if (parameters.length>0){
      for (int i = 0; i < parameters.length; i++) {
        if (args[i]==null){
          span.setTag(i+"_"+parameters[i].getName(),"null");
        }else {
          String value = args[i].toString();
          span.setTag("param_"+i+"_"+parameters[i].getName(), args[i].toString().substring(0,value.length()>=max_size?max_size:value.length()));
        }
        methodName.append(buildTypeName(parameters[i].getType().getTypeName())).append(" ").append(parameters[i].getName());
        if (i<parameters.length-1){
          methodName.append(",");
        }
        if (i==5){
          methodName.append("...");
          break;
        }
      }
    }
    methodName.append(")");
    span.setTag("method_name",methodName.toString());
    span.setResourceName(DECORATE.getMethodName(className)+"."+methodName.toString());
  }

  private String buildTypeName(String typeName){
    return typeName.substring(typeName.lastIndexOf('.') + 1);
  }

  private void addTagResource(AgentSpan span,Method method,String className){
    span.setResourceName(DECORATE.getMethodName(className)+"."+method.getName());
  }

  public String getMethodName(String className) {
    String[] packageParts = className.substring(0, className.lastIndexOf('.')).split("\\.");
    StringBuilder abbreviatedPackageName = new StringBuilder();
    for (String part : packageParts) {
      if (!part.isEmpty()) {
        abbreviatedPackageName.append(part.charAt(0)).append(".");
      }
    }
    // 保留最后的类名
    String simpleClassName = className.substring(className.lastIndexOf('.') + 1);
    return abbreviatedPackageName + simpleClassName;
  }
}
