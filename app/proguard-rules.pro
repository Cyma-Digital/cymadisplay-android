# Add project specific ProGuard rules here.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class * {
    @androidx.media3.common.util.UnstableApi *;
}

# ── kotlinx.serialization ───────────────────────────────────────────────────
# Without these, R8 strips/renames the generated $serializer classes and the
# Companion.serializer() lookups fail at runtime — Json.encodeToString then
# emits an empty body or one with obfuscated field names, which is why the
# release build sends no dsCodigo / codigoPareamento.

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Defensive: keep generated serializer classes and Companion fields for our DTOs.
-keep,includedescriptorclasses class com.cyma.videoloop.**$$serializer { *; }
-keepclassmembers class com.cyma.videoloop.** {
    *** Companion;
}
-keepclasseswithmembers class com.cyma.videoloop.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Retrofit 2.9 ────────────────────────────────────────────────────────────
# 2.9.0 ships no consumer rules (those landed in 2.10.0). Without them, R8
# full mode strips generic signatures from `Continuation<T>` and Retrofit's
# suspend-function support throws
#   ClassCastException: Class cannot be cast to ParameterizedType
# when introspecting the API method to determine the response type.

-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# R8 full mode strips generic signatures from non-kept classes. Suspend
# functions wrap the response in `Continuation<T>`; we need T preserved.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Keep Retrofit-annotated interface methods (they're called via reflection
# from the dynamic Proxy, so R8 can't see them and would otherwise strip
# the methods or replace the interface with null).
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
