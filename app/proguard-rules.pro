-keep class com.stenograf.app.asr.TinyAsrNative { *; }
-keep class com.stenograf.app.data.model.** { *; }
-keepattributes *Annotation*
-dontwarn kotlinx.serialization.**

-keep class com.k2fsa.sherpa.onnx.** { *; }
