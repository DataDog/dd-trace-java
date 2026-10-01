package com.datadog.debugger.jaxrs;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;

@Path("myresource")
public class MyResource {

  @GET
  public Object createResource(
      String apiKey, @Context String uriInfo, @QueryParam("value") int value) {
    String varStr = "foo";
    Object response = new Object();
    return response;
  }
}
