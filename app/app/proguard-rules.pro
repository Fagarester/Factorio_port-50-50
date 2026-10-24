# Project-specific ProGuard rules.
# Native (JNI) methods are kept by the default proguard-android.txt rules.

# Vortek / legacy Gladio: JNI looks these up by name
-keep class com.factorio.xenvironment.components.VortekRendererComponent { *; }
-keep class com.factorio.xenvironment.components.VortekRendererComponent$Options { *; }
-keep class com.factorio.xserver.extensions.LegacyGLXExtension { *; }
