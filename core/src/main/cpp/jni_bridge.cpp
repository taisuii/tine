//
// Created by canyie on 2020/3/18.
//

#include "jni_bridge.h"
#include "utils/macros.h"
#include "utils/scoped_local_ref.h"

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (UNLIKELY(vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)) {
        return JNI_ERR;
    }

    {
        ScopedLocalClassRef Tine(env, "com/android/tine/Tine");
        if (UNLIKELY(Tine.IsNull())) {
            return JNI_ERR;
        }
        if (UNLIKELY(!register_Tine(env, Tine.Get()))) {
            return JNI_ERR;
        }
    }

    {
        ScopedLocalClassRef Ruler(env, "com/android/tine/Ruler");
        if (UNLIKELY(Ruler.IsNull())) {
            return JNI_ERR;
        }
        if (UNLIKELY(!register_Ruler(env, Ruler.Get()))) {
            return JNI_ERR;
        }
    }

    return JNI_VERSION_1_6;
}