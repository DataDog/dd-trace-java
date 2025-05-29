runner {
  parallel {
    enabled true

    // Runtime.getRuntime().availableProcessors() is used to scale the parallelism by default
    // but it returns weird values in Gitlab/kubernetes so fix the parallelism to a specific value
    if (System.getenv("RUNTIME_AVAILABLE_PROCESSORS_OVERRIDE") != null) {
      fixed(Integer.valueOf(System.getenv("RUNTIME_AVAILABLE_PROCESSORS_OVERRIDE")))
    }
  }
}
