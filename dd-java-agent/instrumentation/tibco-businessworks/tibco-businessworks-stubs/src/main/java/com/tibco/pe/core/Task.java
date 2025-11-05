package com.tibco.pe.core;

import com.tibco.pe.plugin.Activity;

public interface Task {
  String getName();

  Activity getActivity();

  Workflow getWorkflow();

  int getTransitionCount();
}
