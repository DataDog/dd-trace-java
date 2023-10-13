class HierarchyMatcherGrpcStreamingTest extends GrpcStreamingTest {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.integration.grpc.matching.shortcut.enabled", "false")
  }

  @Override
  int version() {
    return 0
  }

  @Override
  String clientOperation() {
    return "grpc.client"
  }

  @Override
  String serverOperation() {
    return "grpc.server"
  }
}
