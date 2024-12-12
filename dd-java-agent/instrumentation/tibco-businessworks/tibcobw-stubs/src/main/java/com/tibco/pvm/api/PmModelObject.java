package com.tibco.pvm.api;

import com.tibco.pvm.api.session.PmContext;

public interface PmModelObject extends PmAttributed {
  String getName(PmContext pmContext);
}
