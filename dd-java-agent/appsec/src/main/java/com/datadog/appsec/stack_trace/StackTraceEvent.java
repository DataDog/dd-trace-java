package com.datadog.appsec.stack_trace;

import java.util.List;

public class StackTraceEvent {

  public final String id;
  public final StackTraceEventType type;
  public final String language = "java";
  public final String message;
  public final List<Frame> frames;

  public StackTraceEvent(String id, StackTraceEventType type, String message, List<Frame> frames) {
    this.id = id;
    this.type = type;
    this.message = message;
    this.frames = frames;
  }

  public static class Frame {
    public final int id;
    public final String text;
    public final String file;
    public final int line;
    public final String class_name;
    public final String function;

    public Frame(StackTraceElement element, int id) {
      this.id = id;
      this.text = element.toString();
      this.file = element.getFileName();
      this.line = element.getLineNumber();
      this.class_name = element.getClassName();
      this.function = element.getMethodName();
    }
  }
}
