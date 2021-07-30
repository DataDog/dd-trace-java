class HierarchyMatcherGrpcStreamingTest extends GrpcStreamingTest {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.grpc.matching.shortcut.enabled", "false")
  }
}
