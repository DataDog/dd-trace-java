use datadog_library_config::{Configurator, LibraryConfig, LibraryConfigSource, ProcessInfo};
use jni::{
    objects::{JClass, JMap, JObject, JString, JValue},
    sys::{jboolean, jlong},
    JNIEnv,
};

struct JavaConfigurator {
    fleet_path: String,
    local_path: String,
    configurator: Configurator,
}

#[allow(non_snake_case)]
#[no_mangle]
pub fn Java_JavaConfigurator_new_1configurator<'local>(
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
pub fn Java_JavaConfigurator_drop<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    configurator: jlong,
) {
    drop(unsafe { Box::from_raw(configurator as *mut JavaConfigurator) });
}

#[allow(non_snake_case)]
#[no_mangle]
pub fn Java_JavaConfigurator_override_1local_1path<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    configurator: jlong,
    local_path: JString<'local>,
) {
    try_java(&mut env, |env| {
        let configurator = unsafe {
            (configurator as *mut JavaConfigurator)
                .as_mut::<'local>()
                .unwrap()
        };
        configurator.local_path = env.get_string(&local_path)?.into();
        Ok(())
    })
}

#[allow(non_snake_case)]
#[no_mangle]
pub fn Java_JavaConfigurator_override_1fleet_1path<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    configurator: jlong,
    fleet_path: JString<'local>,
) {
    try_java(&mut env, |env| {
        let configurator = unsafe {
            (configurator as *mut JavaConfigurator)
                .as_mut::<'local>()
                .unwrap()
        };
        configurator.fleet_path = env.get_string(&fleet_path)?.into();
        Ok(())
    })
}

fn jmap_put_config<'local, 'inner>(
    env: &mut JNIEnv<'local>,
    cfg: LibraryConfig,
    jmap: &JMap<'local, 'inner, 'inner>,
) -> anyhow::Result<()> {
    jmap.put(
        env,
        env.new_string(cfg.name.to_str())?.as_ref(),
        env.new_string(cfg.value)?.as_ref(),
    )?;
    Ok(())
}

fn library_configs_to_java_maps<'local>(
    env: &mut JNIEnv<'local>,
    config: Vec<LibraryConfig>,
) -> anyhow::Result<(JObject<'local>, JObject<'local>, Option<String>)> {
    let fleet_map = env.new_object("java/util/HashMap", "()V", &[])?;
    let fleet_jmap = jni::objects::JMap::from_env(env, &fleet_map)?;

    let local_map = env.new_object("java/util/HashMap", "()V", &[])?;
    let local_jmap = jni::objects::JMap::from_env(env, &local_map)?;

    let mut config_id = None;
    for mut cfg in config {
        let source = cfg.source;
        config_id = config_id.or(cfg.config_id.take());
        jmap_put_config(
            env,
            cfg,
            match &source {
                LibraryConfigSource::FleetStableConfig => &fleet_jmap,
                LibraryConfigSource::LocalStableConfig => &local_jmap,
            },
        )?;
    }
    Ok((fleet_map, local_map, config_id))
}

#[allow(non_snake_case)]
#[no_mangle]
pub fn Java_JavaConfigurator_get_1configuration<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    configurator: jlong,
    stable_config: JObject<'local>,
) {
    try_java(&mut env, |env| {
        let configurator = unsafe {
            (configurator as *mut JavaConfigurator)
                .as_mut::<'local>()
                .unwrap()
        };
        let config = configurator.configurator.get_config_from_file(
            configurator.local_path.as_ref(),
            configurator.fleet_path.as_ref(),
            ProcessInfo::detect_global("java".to_owned()),
        )?;

        let (fleet_map, local_map, config_id) = library_configs_to_java_maps(env, config)?;

        env.set_field(
            &stable_config,
            "fleet_configuration",
            "Ljava/util/Map;",
            JValue::Object(&fleet_map),
        )?;
        env.set_field(
            &stable_config,
            "local_configuration",
            "Ljava/util/Map;",
            JValue::Object(&local_map),
        )?;

        if let Some(config_id) = config_id {
            env.set_field(
                &stable_config,
                "config_id",
                "Ljava/lang/String;",
                JValue::Object(env.new_string(config_id)?.as_ref()),
            )?;
        }

        Ok(())
    })
}

fn try_java<'local, T: Default, F: FnOnce(&mut JNIEnv<'local>) -> anyhow::Result<T>>(
    env: &mut JNIEnv<'local>,
    f: F,
) -> T {
    match f(env) {
        Ok(o) => o,
        Err(e) => {
            env.throw(format!("{e}")).unwrap();
            T::default()
        }
    }
}
