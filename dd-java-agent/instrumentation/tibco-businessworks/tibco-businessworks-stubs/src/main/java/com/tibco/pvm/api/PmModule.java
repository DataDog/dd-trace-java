package com.tibco.pvm.api;

import com.tibco.pvm.api.session.PmContext;

public interface PmModule extends PmModelObject {
  PmModule getPrototype(PmContext pmContext);
}
