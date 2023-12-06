#include <jvmti.h>
#include <string.h>
#include <unistd.h>

#define ACC_STATIC 0x0008

static inline void
check_java_exception(JNIEnv* env)
{
    if((*env)->ExceptionOccurred(env)) {
        (*env)->ExceptionDescribe(env);
    }
}

jclass find_class(JNIEnv* jni_env, char* name) {
    jclass clazz = (*jni_env)->FindClass(jni_env, name);
    if (clazz == NULL) {
      printf("FindClass %s error\n", name);
      (*jni_env)->ExceptionClear(jni_env);
      return NULL;
    }
    return (jclass)(*jni_env)->NewGlobalRef(jni_env, clazz);
}

jmethodID get_static_method_id(JNIEnv* jni_env, jclass clazz, char* name, char* sig) {
  jmethodID methodId = (*jni_env)->GetStaticMethodID(jni_env, clazz, name, sig);
     if (methodId == NULL) {
      printf("GetStaticMethodId %s error\n", name);
      (*jni_env)->ExceptionClear(jni_env);
      return NULL;
    }
    return (jclass)(*jni_env)->NewGlobalRef(jni_env, methodId);
}

jmethodID get_method_id(JNIEnv* jni_env, jclass clazz, char* name, char* sig) {
  jmethodID methodId = (*jni_env)->GetMethodID(jni_env, clazz, name, sig);
     if (methodId == NULL) {
      printf("GetMethodId %s error\n", name);
      (*jni_env)->ExceptionClear(jni_env);
      return NULL;
    }
    return (jclass)(*jni_env)->NewGlobalRef(jni_env, methodId);
}

// return number of args in method signature
int scan_args(char* method_sig) {
  int result = 0;
  while (*method_sig != '\0' && *method_sig != ')') {
    if (*method_sig == '[') {
      method_sig++;
      continue;
    }
    if (*method_sig == 'L') { 
        // Skip until the semicolon to find the end of the class name
        while (*method_sig != '\0' && *method_sig != ';') {
            method_sig++;
        }
    } 
    result++;
    method_sig++;
  }
  return result;
}

jvmtiEnv* jvmti = NULL;
unsigned int can_get_line_numbers = 0;
jclass CapturedContext_class = NULL;
jmethodID CapturedContext_init = NULL;
jclass CapturedContextHelper_class = NULL;
jmethodID CapturedContextHelper_addArg = NULL;
jmethodID CapturedContextHelper_addArgI = NULL;
jmethodID CapturedContextHelper_addLocal = NULL;
jmethodID CapturedContextHelper_addLocalI = NULL;
jmethodID CapturedContextHelper_commit = NULL;
jmethodID CapturedContextHelper_addException = NULL;

volatile int count = 0;

void JNICALL exceptionEvent(jvmtiEnv* jvmti_env, JNIEnv* jni_env, jthread thread, jmethodID method, jlocation location, jobject exception, jmethodID catch_method, jlocation catch_location) {
  // prepare CapturedContext  TODO: should put init VMstart event
  if (CapturedContextHelper_class == NULL) {
    CapturedContextHelper_class = find_class(jni_env, "Ldatadog/trace/bootstrap/debugger/CapturedContextHelper;");
  }
  if (CapturedContextHelper_class  != NULL && CapturedContextHelper_addArg == NULL) {
    CapturedContextHelper_addArg = get_static_method_id(jni_env, CapturedContextHelper_class, "addArg", "(Ldatadog/trace/bootstrap/debugger/CapturedContext;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V");
  }
  if (CapturedContextHelper_class  != NULL && CapturedContextHelper_addArgI == NULL) {
    CapturedContextHelper_addArgI = get_static_method_id(jni_env, CapturedContextHelper_class, "addArg", "(Ldatadog/trace/bootstrap/debugger/CapturedContext;Ljava/lang/String;Ljava/lang/String;I)V");
  }
  if (CapturedContextHelper_class  != NULL && CapturedContextHelper_addLocalI == NULL) {
    CapturedContextHelper_addLocalI = get_static_method_id(jni_env, CapturedContextHelper_class, "addLocal", "(Ldatadog/trace/bootstrap/debugger/CapturedContext;Ljava/lang/String;Ljava/lang/String;I)V");
  }
  if (CapturedContextHelper_class  != NULL && CapturedContextHelper_addLocal == NULL) {
    CapturedContextHelper_addLocal = get_static_method_id(jni_env, CapturedContextHelper_class, "addLocal", "(Ldatadog/trace/bootstrap/debugger/CapturedContext;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V");
  }
  if (CapturedContextHelper_class  != NULL && CapturedContextHelper_commit == NULL) {
    CapturedContextHelper_commit = get_static_method_id(jni_env, CapturedContextHelper_class, "commit", "(Ldatadog/trace/bootstrap/debugger/CapturedContext;Ljava/lang/String;I)V");
  }
  if (CapturedContextHelper_class  != NULL && CapturedContextHelper_addException == NULL) {
    CapturedContextHelper_addException = get_static_method_id(jni_env, CapturedContextHelper_class, "addException", "(Ldatadog/trace/bootstrap/debugger/CapturedContext;Ljava/lang/Throwable;)V");
  }
  if (CapturedContext_class == NULL) {
    CapturedContext_class = find_class(jni_env, "Ldatadog/trace/bootstrap/debugger/CapturedContext;");
  }
  if (CapturedContext_class != NULL && CapturedContext_init == NULL) {
    CapturedContext_init = get_method_id(jni_env, CapturedContext_class, "<init>", "()V");
  }
  // get the Declaring class + signature to be able to filter out from package names
  jclass declaring_class;
  if ((*jvmti)->GetMethodDeclaringClass(jvmti, method, &declaring_class) != 0) {
    printf("GetMethodDeclaringClass error\n");
    return;
  }
  char* declaring_class_sig;
  if ((*jvmti)->GetClassSignature(jvmti, declaring_class, &declaring_class_sig, NULL) != 0) {
    printf("GetClassSignature error\n");
    return;
  }
  // TODO deallocate declaring_class_sig
  // filter out exception
  if (strncmp(declaring_class_sig, "Ljava/", strlen("Ljava/")) == 0) {
    return;
  }
  if (strncmp(declaring_class_sig, "Ljavax/", strlen("Ljavax/")) == 0) {
    return;
  }
  if (strncmp(declaring_class_sig, "Lsun/", strlen("Lsun/")) == 0) {
    return;
  }
  if (strncmp(declaring_class_sig, "Ldatadog/", strlen("Ldatadog/")) == 0) {
    return;
  }
  // specific filtering
  if (strncmp(declaring_class_sig, "Lorg/springframework/boot/loader/jar/JarURLConnection;", strlen("Lorg/springframework/boot/loader/jar/JarURLConnection;")) == 0) {
    return;
  }
  if (strncmp(declaring_class_sig, "Lorg/springframework/boot/loader/LaunchedURLClassLoader;", strlen("Lorg/springframework/boot/loader/LaunchedURLClassLoader;")) == 0) {
    return;
  }
  
  printf("declaring class: %s\n", declaring_class_sig);
  printf("exception event: method[%p] location[%ld] catch_method[%p] catch_location[%ld]\n", method, location, catch_method, catch_location);
  // get the method where the exception was thrown
  char* method_name;
  char* method_sig;
  char* generic_sig;
  if ((*jvmti)->GetMethodName(jvmti, method, &method_name, &method_sig, &generic_sig) != 0) {
    printf("GetMethodName error\n");
    return;
  }
  // get line number
  jint line_number = -1;
  if (can_get_line_numbers) {
    jint entry_count;
    jvmtiLineNumberEntry* line_number_table;
    if ((*jvmti)->GetLineNumberTable(jvmti, method, &entry_count, &line_number_table) != 0) {
      printf("GetLineNumberTable error\n");
      return;
    }
    printf("entry_count=%d\n", entry_count);
    line_number = line_number_table[0].line_number;
    for (int i = 1 ; i < entry_count ; i++ ) {
        if (location < line_number_table[i].start_location ) {
            break;
        }
        line_number = line_number_table[i].line_number;
    }
    (*jvmti)->Deallocate(jvmti, line_number_table);
  }
  char* source_filename; // TODO deallocate source_filename
  if ((*jvmti)->GetSourceFileName(jvmti, declaring_class, &source_filename) != 0) {
    printf("GetSourceFileName error\n");
    return;
  }
  printf("method name: %s::%s%s(%s:%d)\n", declaring_class_sig, method_name, method_sig, source_filename, line_number);
  jint method_access_flags;
  if ((*jvmti)->GetMethodModifiers(jvmti, method, &method_access_flags) != 0) {
    printf("GetMethodModifiers error\n");
    return;
  }
  // scan for nb args
  int nb_args = 0;
  if (method_access_flags & ACC_STATIC == 0) {
    // instance method add 'this' as arg
    nb_args++;
  }
  nb_args += scan_args(method_sig);
  printf("nbargs=%d\n", nb_args);
  (*jvmti)->Deallocate(jvmti, method_name);
  (*jvmti)->Deallocate(jvmti, method_sig);
  (*jvmti)->Deallocate(jvmti, generic_sig);

  // get current frame and get local variables
#define FRAME_DEPTH 1
  jvmtiFrameInfo frames[FRAME_DEPTH];
  jint frame_count;
  if ((*jvmti)->GetStackTrace(jvmti, thread, 0, FRAME_DEPTH, &frames, &frame_count) != 0) {
    printf("GetStackTrace error\n");
    return;
  }
  printf("frames=%d\n", frame_count);
  if (CapturedContext_class == NULL) {
    return;
  }
  for (int i = 0; i < frame_count; i++) {
    jint local_var_entry_count;
    jvmtiLocalVariableEntry* local_var_table;
    if ((*jvmti)->GetLocalVariableTable(jvmti, method, &local_var_entry_count, &local_var_table) != 0) {
      printf("GetLocalVariableTable error\n");
      return;
    }
    // allocate CapturedContext
    jobject context = (*jni_env)->NewObject(jni_env, CapturedContext_class, CapturedContext_init);
    if (context == NULL) {
      printf("NewObject error\n");
      (*jni_env)->ExceptionClear(jni_env);
      return;
    }
    // add as uncaught exception the current exception
    if (i == 0) {
      (*jni_env)->CallStaticVoidMethod(jni_env, CapturedContextHelper_class, CapturedContextHelper_addException, context, exception);
      check_java_exception(jni_env);
    }
    // collect args & locals
    printf("local var entries=%d\n", local_var_entry_count);
    for (int entry_idx = 0; entry_idx < local_var_entry_count; entry_idx++) {
      jvmtiLocalVariableEntry entry = local_var_table[entry_idx];
      int is_arg = entry_idx < nb_args;
      switch (entry.signature[0]) {
        case 'B':
        case 'C':
        case 'S':
        case 'Z':
        case 'I': {
          jint value;
          (*jvmti)->GetLocalInt(jvmti, thread, i, entry.slot, &value);
          printf("LovalVar[%d] name=%s value=%d\n", entry.slot, entry.name, value);
          jstring entry_name = (*jni_env)->NewStringUTF(jni_env, entry.name);
          jstring entry_signature = (*jni_env)->NewStringUTF(jni_env, entry.signature);
          jmethodID add_method = is_arg ? CapturedContextHelper_addArgI : CapturedContextHelper_addLocalI;
          (*jni_env)->CallStaticVoidMethod(jni_env, CapturedContextHelper_class, add_method, context, entry_name, entry_signature, value);
          check_java_exception(jni_env);
          break;
        }
        case 'F': {
          jfloat value;
          (*jvmti)->GetLocalFloat(jvmti, thread, i, entry.slot, &value);
          printf("LovalVar[%d] name=%s value=%f\n", entry.slot, entry.name, value);
          break;
        }
        case 'D': {
          jdouble value;
          (*jvmti)->GetLocalDouble(jvmti, thread, i, entry.slot, &value);
          printf("LovalVar[%d] name=%s value=%f\n", entry.slot, entry.name, value);
          break;
        }
        case 'J': {
          jlong value;
          (*jvmti)->GetLocalLong(jvmti, thread, i, entry.slot, &value);
          printf("LovalVar[%d] name=%s value=%ld\n", entry.slot, entry.name, value);
          break;
        }
        case 'L': {
          jobject value;
          int error;
          if ((error = (*jvmti)->GetLocalObject(jvmti, thread, i, entry.slot, &value)) != 0) {
            printf("getLocalObject on slot[%d] error: %d\n", entry.slot, error);
            continue;
          }
          if (value == NULL) {
            printf("LocalObject null\n");
            continue;
          }
          jclass clazz = (*jni_env)->GetObjectClass(jni_env, value);
          if (clazz == NULL) {
            printf("GetObjectClass error\n");
            (*jni_env)->ExceptionClear(jni_env);
            continue;
          }
          char* class_sig;
          if ((*jvmti)->GetClassSignature(jvmti, clazz, &class_sig, NULL) != 0) {
            printf("GetClassSignature error\n");
            continue;
          }
          printf("LocalVar[%d] name=%s value=instance of %s\n", entry.slot, entry.name, class_sig);
          jstring entry_name = (*jni_env)->NewStringUTF(jni_env, entry.name);
          jstring entry_signature = (*jni_env)->NewStringUTF(jni_env, entry.signature);
          jmethodID add_method = is_arg ? CapturedContextHelper_addArg : CapturedContextHelper_addLocal;
          (*jni_env)->CallStaticVoidMethod(jni_env, CapturedContextHelper_class, add_method, context, entry_name, entry_signature, value);
          check_java_exception(jni_env);
          (*jvmti)->Deallocate(jvmti, class_sig);
          break;
        }
        case '[': {
          printf("LocalVar[%d] name=%s is an array\n", entry.slot, entry.name);
          break;
        }
        default: {
          printf("not supported signature: %s\n", entry.signature);
        }
      }
    }
    (*jvmti)->Deallocate(jvmti, local_var_table);
      // Upcall to commit the CaputredContext
    jstring source_filename_str = (*jni_env)->NewStringUTF(jni_env, source_filename);
    (*jni_env)->CallStaticVoidMethod(jni_env, CapturedContextHelper_class, CapturedContextHelper_commit, context, source_filename_str, line_number);
    check_java_exception(jni_env);
  }
}

void JNICALL VMInit(jvmtiEnv *jvmti_env, JNIEnv* jni_env, jthread thread) {
// event called just before starting Main thread. Main thread does not start until this event handler is finished
}


JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void* reserved) {
  printf("exception agent on load begin\n");
  if ((*vm)->GetEnv(vm, (void**)&jvmti, JVMTI_VERSION_1_0) != 0) {
    printf("GetEnv error\n");
    return -1;
  }
  jvmtiCapabilities capabilities = {0};
  if ((*jvmti)->GetPotentialCapabilities(jvmti, &capabilities) != 0) {
    printf("GetCapabilities error\n");
    return -1;
  }
  can_get_line_numbers = capabilities.can_get_line_numbers;
  printf("can_get_line_numbers=%d\n", can_get_line_numbers);
  capabilities.can_generate_exception_events = 1;
  if ((*jvmti)->AddCapabilities(jvmti, &capabilities) != 0) {
    printf("AddCapabilities error\n");
    return -1;
  }

  jvmtiEventCallbacks callbacks = {0};
  callbacks.Exception = &exceptionEvent;
  //callbacks.VMInit = &vmInit;
  if ((*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(callbacks)) != 0) {
    printf("SetEventCallsbacks error\n");
    return -1;
  }
  if ((*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_EXCEPTION, NULL) != 0) {
    printf("SetEventNotificationMode error\n");
    return -1;
  }
  printf("setup agent done\n");
  return 0;
}

int main(int argc, char** argv) {
  return 0;
}
