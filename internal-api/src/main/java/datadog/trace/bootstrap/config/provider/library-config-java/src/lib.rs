use datadog_library_config::{Configurator, ProcessInfo};
use jni::{
    objects::JClass,
    sys::{jboolean, jint, jlong},
    JNIEnv,
};

struct JavaConfigurator {
    fleet_path: String,
    local_path: String,
    configurator: Configurator,
}

#[allow(non_snake_case)]
#[no_mangle]
pub fn Java_FfiStableConfig_new_1configurator<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    debug_logs: jboolean,
) -> jlong {
    let configurator = Configurator::new(debug_logs != 0);
    Box::into_raw(Box::new(JavaConfigurator {
        configurator,
        fleet_path: Configurator::FLEET_STABLE_CONFIGURATION_PATH.to_owned(),
        local_path: Configurator::LOCAL_STABLE_CONFIGURATION_PATH.to_owned(),
    })) as jlong
}

#[allow(non_snake_case)]
#[no_mangle]
pub fn Java_FfiStableConfig_get_1configuration<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    configurator: jlong,
) -> jlong {
    let configurator = unsafe {
        (configurator as *mut JavaConfigurator)
            .as_mut::<'local>()
            .unwrap()
    };
    let config = configurator.configurator.get_config_from_file(
        configurator.local_path.as_ref(),
        configurator.fleet_path.as_ref(),
        ProcessInfo::detect_global("java".to_owned()),
    );
    config.map(|c| c.len()).unwrap_or(0) as jlong
}
