#include <android/log.h>
#include <jni.h>

#include <tdebuglistener.h>

#include "jni_bridge.h"

namespace {

/** TagLib's debug messages (debug builds only) go to stderr, which Android drops; send them to logcat instead. */
class LogcatDebugListener : public TagLib::DebugListener {
 public:
  void printMessage(const TagLib::String &message) override {
    __android_log_print(ANDROID_LOG_DEBUG, "TagLib", "%s", message.toCString(true));
  }
};

LogcatDebugListener debugListener;

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  // Installed once, before any thread uses TagLib: the listener pointer is global and not atomic.
  TagLib::setDebugListener(&debugListener);
  if (!piplayer::registerTagReader(env) || !piplayer::registerChromaprint(env) ||
      !piplayer::registerResampler(env)) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}
