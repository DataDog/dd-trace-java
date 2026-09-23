package com.microsoft.azure.functions.worker.chain;

import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareChain;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext;

public class FunctionExecutionMiddleware {
  public void invoke(MiddlewareContext context, MiddlewareChain chain) throws Exception {
    chain.doNext(context);
  }
}
