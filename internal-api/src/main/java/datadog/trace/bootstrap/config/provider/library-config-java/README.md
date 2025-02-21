# Library config Java
This is a small module to interface dd-trace-java with libdatadog. To use locally:

1. Install [Rust](https://www.rust-lang.org/tools/install) 
2. From the root, run the following command to build the native library
```
cargo build --manifest-path internal-api/src/main/java/datadog/trace/bootstrap/config/provider/library-config-java/Cargo.toml --release
```
3. Move the native library to the corresponding native dir of your machine. Example for Linux/aarch64/glibc:
```
mv internal-api/src/main/java/datadog/trace/bootstrap/config/provider/library-config-java/target/release/libdatadog_library_config_java.so native_libs/linux/aarch64/glibc
```

Note that the extension of the generated file (.so / .dll / .dylib) depends on your OS.
