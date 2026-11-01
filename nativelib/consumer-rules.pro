# Native code finds these classes by name through JNI and registers their native methods in JNI_OnLoad
# (chromaprint_jni.cpp, resampler_jni.cpp, taglib_jni.cpp). If R8 removed or renamed any of them, loading the library
# would fail for all of them, so they are kept whole, even when the app does not use one.
-keep class io.github.lorddetson.piplayer.nativelib.chromaprint.ChromaprintSession { native <methods>; }
-keep class io.github.lorddetson.piplayer.nativelib.resample.Resampler { native <methods>; }
-keep class io.github.lorddetson.piplayer.nativelib.taglib.TagReader { native <methods>; }

# Native code calls these back by name through JNI.
-keep class io.github.lorddetson.piplayer.nativelib.taglib.TagSink { *; }
