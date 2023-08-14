package datadog.smoketest

class IastSpringBootOptOutSmokeTest extends AbstractIastSpringBootTest {

  @Override
  protected String iastEnabledFlag() {
    return 'opt-out'
  }
}
