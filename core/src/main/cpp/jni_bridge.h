//
// Created by canyie on 2020/3/18.
//

#ifndef TINE_JNI_BRIDGE_H
#define TINE_JNI_BRIDGE_H

#include <jni.h>

extern "C" {
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved);
bool register_Tine(JNIEnv* env, jclass Tine);
bool register_Ruler(JNIEnv* env, jclass Ruler);

void Ruler_m1(JNIEnv* env, jclass, jfloat); // used for search ArtMethod members
}

#endif //TINE_JNI_BRIDGE_H
