import datadog.smoketest.AbstractIastSpringBootTest

class IastSpringBootOptOutSmokeTest extends AbstractIastSpringBootTest {

  @Override
  protected String iastEnabledFlag() {
    return 'opt-out'
  }
}
