#include <jni.h>
#include <malloc.h>
#include <android/log.h>

#ifndef M_PURGE
#define M_PURGE -101
#endif
#ifndef M_PURGE_ALL
#define M_PURGE_ALL -104
#endif

JNIEXPORT jboolean JNICALL
Java_com_raen_kisaratranslator_data_monitor_ResourceMonitor_nativePurgeHeap(JNIEnv *env, jobject thiz) {
    int r1 = mallopt(M_PURGE_ALL, 0);
    int r2 = mallopt(M_PURGE, 0);
    __android_log_print(ANDROID_LOG_INFO, "KisaraNative", "mallopt purge result: purge_all=%d, purge=%d", r1, r2);
    return (r1 == 1 || r2 == 1) ? JNI_TRUE : JNI_FALSE;
}
