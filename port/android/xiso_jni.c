#include <jni.h>
#include <stddef.h>
#include <stdio.h>

#include "../ios/xiso.h"

/*
 * Keep XISO parsing in the same hardened implementation used by the iOS
 * launcher. Java copies a Storage Access Framework URI to app-owned storage,
 * then calls this bridge with ordinary filesystem paths.
 */
JNIEXPORT jstring JNICALL
Java_org_libsdl_app_XisoNative_extractXiso(JNIEnv *env, jclass clazz,
                                           jstring image_path,
                                           jstring destination_path)
{
    const char *image;
    const char *destination;
    char error[512] = {0};
    int rc;

    (void)clazz;
    if (image_path == NULL || destination_path == NULL)
        return (*env)->NewStringUTF(env, "Missing image or destination path.");

    image = (*env)->GetStringUTFChars(env, image_path, NULL);
    if (image == NULL)
        return (*env)->NewStringUTF(env, "Could not read the image path.");

    destination = (*env)->GetStringUTFChars(env, destination_path, NULL);
    if (destination == NULL) {
        (*env)->ReleaseStringUTFChars(env, image_path, image);
        return (*env)->NewStringUTF(env, "Could not read the destination path.");
    }

    rc = xiso_extract(image, destination, NULL, NULL, error, sizeof(error));

    (*env)->ReleaseStringUTFChars(env, destination_path, destination);
    (*env)->ReleaseStringUTFChars(env, image_path, image);

    if (rc == 0)
        return (*env)->NewStringUTF(env, "");
    if (error[0] == '\0')
        snprintf(error, sizeof(error), "Could not unpack the Xbox disc image.");
    return (*env)->NewStringUTF(env, error);
}
