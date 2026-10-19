#pragma once

#include <jni.h>

namespace piplayer {

/** Registers the TagReader natives and caches the TagSink callbacks; returns false on failure. */
bool registerTagReader(JNIEnv *env);

/** Registers the ChromaprintSession natives; returns false on failure. */
bool registerChromaprint(JNIEnv *env);

}  // namespace piplayer
