package com.tibco.pe.core;

import com.tibco.pe.plugin.ProcessContext;

public class DDJobMate {
  public static Workflow getJobWorkflow(final ProcessContext job) {
    if (!(job instanceof Job)) {
      return null;
    }
    return ((Job) job).getActualWorkflow();
  }
}
