package com.tibco.pe.core;

public interface Workflow {
  String getName();

  Task getGroupEnd(String str);

  Task getStartTask();
}
