package com.tibco.pvm.api;

import com.tibco.pvm.api.session.PmContext;

public interface PmProcessInstance extends PmProcess {
  PmProcessInstance getParentProcess(PmContext pmContext);

  PmTask getSpawner(PmContext pmContext);
}
