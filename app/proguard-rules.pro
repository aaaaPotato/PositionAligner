# sherpa-onnx JNI 类需要保留
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# pdfbox
-keep class org.apache.pdfbox.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.apache.pdfbox.**
-dontwarn org.bouncycastle.**

# Compose / Kotlin
-keepattributes *Annotation*
-dontwarn javax.annotation.**