# JNI resolves these by symbol name at runtime, so R8 must not rename them.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.ishaan.essentialvoice.whisper.WhisperLib { *; }

# Entry points the framework constructs by name are kept via the manifest, but
# the accessibility service is also reached through a static instance field.
-keep class com.ishaan.essentialvoice.trigger.EssentialKeyService { *; }

# Kotlin coroutines' debug agent probes for these; harmless to keep quiet about.
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
