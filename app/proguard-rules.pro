# R8 rules for release/benchmark builds.
# Libraries below call into native code (JNI) or look classes up by name, so R8 can't see that
# they're used: keep them whole. App code and the AndroidX/Kotlin libraries are shrunk normally
# (they ship their own consumer rules).

# MediaPipe tasks (smart search embedder) and its native retrieval/icing pieces.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.android.icing.** { *; }
-dontwarn com.google.mediapipe.**

# LiteRT-LM: MediaPipe's embedder depends on it; JNI callbacks and Gson-mapped messages.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.gson.** { *; }

# ONNX Runtime (Text in photos, Audio): the native library builds these classes from C++.
-keep class ai.onnxruntime.** { *; }

# Protobuf lite messages are read by field name.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Flogger finds its backend by name at run time.
-keep class com.google.common.flogger.** { *; }

# PdfBox loads fonts, encodings and filters by name.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# Optional dependencies these libraries reference but don't need on Android.
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Readable stack traces in warnings (no file names or user content are ever logged).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
