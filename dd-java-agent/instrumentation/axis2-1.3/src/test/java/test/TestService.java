package test;

import org.apache.axiom.om.OMElement;

public class TestService {
  public void testAction(final OMElement element) {
    System.out.println("Received action message");
  }

  public void testFault(final OMElement element) {
    System.out.println("Received fault message");
  }
}
