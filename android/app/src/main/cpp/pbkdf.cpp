#include <jni.h>
#include <openssl/evp.h>
#include <openssl/mem.h>
#include <vector>

// Use the upstream PBKDF2 implementation. No custom cipher or password derivation algorithm.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_id_nusastudio_vault_NativeKdf_derive(JNIEnv* env, jclass, jbyteArray password, jbyteArray salt) {
  if (password == nullptr || salt == nullptr || env->GetArrayLength(salt) != 16) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid KDF input");
    return nullptr;
  }
  std::vector<uint8_t> pass(env->GetArrayLength(password));
  uint8_t salt_bytes[16], key[32];
  env->GetByteArrayRegion(password, 0, pass.size(), reinterpret_cast<jbyte*>(pass.data()));
  env->GetByteArrayRegion(salt, 0, 16, reinterpret_cast<jbyte*>(salt_bytes));
  if (env->ExceptionCheck()) { OPENSSL_cleanse(pass.data(), pass.size()); return nullptr; }
  const int ok = PKCS5_PBKDF2_HMAC(reinterpret_cast<const char*>(pass.data()), pass.size(), salt_bytes,
                                  sizeof(salt_bytes), 600000, EVP_sha256(), sizeof(key), key);
  OPENSSL_cleanse(pass.data(), pass.size());
  if (!ok) {
    OPENSSL_cleanse(key, sizeof(key));
    env->ThrowNew(env->FindClass("java/security/GeneralSecurityException"), "PBKDF2 failed");
    return nullptr;
  }
  jbyteArray result = env->NewByteArray(sizeof(key));
  if (result != nullptr) env->SetByteArrayRegion(result, 0, sizeof(key), reinterpret_cast<jbyte*>(key));
  OPENSSL_cleanse(key, sizeof(key));
  return result;
}
