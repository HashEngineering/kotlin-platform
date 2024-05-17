
#include <jni.h>
#include "config.h"
#include "../../../../dash-sdk-bindings/target/dash_sdk_bindings.h"
#include <cstring>
// fetchIdentity4

//void myFetchIdentity4(platform_value_types_identifier_Identifier *identifier, jobject * callbackObject) {
//    // create lamdas for ContextProvider
//    // Call the right function in jobject
//    platform_mobile_fetch_identity_fetch_identity4(identifier);
//}

jobject contextProvider;
JNIEnv * jenv;
uint8_t invalid_key[] = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
};

uint8_t * get_quorum_public_key(int quorum_type, char * quorum_hash, int core_chain_locked_height, uint8_t * native_array) {
    jclass clazz = jenv->FindClass("org/dashj/platform/sdk/callbacks/ContextProvider");
    if (clazz == nullptr) {
        printf("Cannot find class\n");
    }
    jmethodID getQuorumPublicKey = jenv->GetMethodID(clazz, "getQuorumPublicKey", "(I[BI)[B");
    if (getQuorumPublicKey == nullptr) {
        printf("Cannot find getQuorumPublicKey(IB[I)[B\n");
    }
    jbyteArray quorum_hash_bytes = jenv->NewByteArray(32);
    jenv->SetByteArrayRegion(quorum_hash_bytes, 0, 32, reinterpret_cast<jbyte *>(quorum_hash));

    auto quorum_public_key = (jbyteArray) jenv->CallObjectMethod(contextProvider, getQuorumPublicKey, quorum_type, quorum_hash_bytes, core_chain_locked_height);
    if (quorum_public_key == nullptr) {
        memcpy(native_array, invalid_key, 48);
    } else {
        jbyte* elements = jenv->GetByteArrayElements(quorum_public_key, nullptr);
        if (elements == nullptr) {
            return nullptr;
        }
        jsize length = jenv->GetArrayLength(quorum_public_key);

        memcpy(native_array, elements, length);
        jenv->ReleaseByteArrayElements(quorum_public_key, elements, JNI_ABORT);
        jenv->DeleteLocalRef(quorum_public_key);
//        printf("C++ is reurning this:  ");
//        for(int i = 0; i < 5; i++)
//            printf("%d, ", native_array[i]);
//        printf("\n");
    }
    return native_array;
}

extern "C" JNIEXPORT jlong JNICALL Java_org_dashj_platform_sdk_callbacks_ContextProvider_getQuorumPublicKeyCallback(JNIEnv * env, jclass provider) {
    jenv = env;
    if (contextProvider != nullptr) {
        env->DeleteGlobalRef(contextProvider);
        contextProvider = nullptr;
    }

    contextProvider = env->NewGlobalRef(provider);
    if (contextProvider == nullptr) {
        printf("Failed to create global reference for ContextProvider\n");
    }

    return (long)get_quorum_public_key;
}

jobject signer;

int sign_data(u_int8_t * key_data, int key_len, uint8_t * data, int size, uint8_t * result) {
    printf("sign_data(0x%lx, %d, 0x%lx, %d, result=0x%lx)\n", key_data, key_len, data, size, result);
    jclass clazz = jenv->FindClass("org/dashj/platform/sdk/callbacks/Signer");
    if (clazz == nullptr) {
        printf("Cannot find class\n");
    }
    jmethodID signMethod = jenv->GetMethodID(clazz, "sign", "([B[B)[B");
    if (signMethod == nullptr) {
        printf("Cannot find sign([B[B)[B\n");
    }

    jbyteArray keyByteArray = jenv->NewByteArray(key_len);
    jenv->SetByteArrayRegion(keyByteArray, 0, key_len, reinterpret_cast<jbyte *>(data));

    jbyteArray dataByteArray = jenv->NewByteArray(size);
    jenv->SetByteArrayRegion(dataByteArray, 0, size, reinterpret_cast<jbyte *>(data));
    printf("now call the function in the signer class");
    auto binaryDataObject = (jbyteArray) jenv->CallObjectMethod(signer, signMethod, keyByteArray, dataByteArray);
//    if (quorum_public_key == nullptr) {
//        memcpy(native_array, invalid_key, 48);
//    } else {
//        jbyte* elements = jenv->GetByteArrayElements(quorum_public_key, nullptr);
//        if (elements == nullptr) {
//            return nullptr;
//        }
//        jsize length = jenv->GetArrayLength(quorum_public_key);
//
//        memcpy(native_array, elements, length);
//        jenv->ReleaseByteArrayElements(quorum_public_key, elements, JNI_ABORT);
//        jenv->DeleteLocalRef(quorum_public_key);
////        printf("C++ is reurning this:  ");
////        for(int i = 0; i < 5; i++)
////            printf("%d, ", native_array[i]);
////        printf("\n");
//    }
    printf("sign method called = 0x%lx\n", binaryDataObject);
    // jclass baseObjectClass = jenv->FindClass("org/dashj/platform/sdk/base/BaseObject");
    // if (baseObjectClass == nullptr) {
    //     printf("Cannot find org/dashj/platform/sdk/base/BaseObject\n");
    // }
    // jmethodID getCPointerMethod = jenv->GetMethodID(baseObjectClass, "getCPointer", "()J");
    // if (getCPointerMethod == nullptr) {
    //     printf("Cannot find getCPointer\n");
    // }
    // auto binaryData = (platform_value_types_binary_data_BinaryData*)jenv->CallObjectMethod(binaryDataObject, getCPointerMethod);
    int length = jenv->GetArrayLength(binaryDataObject);
    jenv->GetByteArrayRegion(binaryDataObject, 0, length, (jbyte *)result);

    return length;
}

extern "C" JNIEXPORT jlong JNICALL Java_org_dashj_platform_sdk_callbacks_Signer_getSignerCallback(JNIEnv * env, jclass signerCallback) {
    jenv = env;
    if (signer != nullptr) {
        env->DeleteGlobalRef(signer);
        signer = nullptr;
    }

    signer = env->NewGlobalRef(signerCallback);
    if (signer == nullptr) {
        printf("Failed to create global reference for ContextProvider\n");
    }

    return (long)sign_data;
}