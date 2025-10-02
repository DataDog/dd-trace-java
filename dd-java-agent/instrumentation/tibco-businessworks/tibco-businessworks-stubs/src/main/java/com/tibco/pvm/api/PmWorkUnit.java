package com.tibco.pvm.api;

import com.tibco.pvm.api.session.PmContext;

public interface PmWorkUnit extends PmModelObject {
  PmProcess getProcess(PmContext pmContext);

  PmModule getModule(PmContext pmContext);
}
