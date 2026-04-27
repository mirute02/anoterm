# ========= Security: strip debug/info logs in release builds =========
# android.util.Log の d/v/i を R8 に「副作用なし」として削除させる。
# これで sshj / slf4j-android の DEBUG/INFO 経路も含めて、release APK には
# 一切の verbose ログが残らない（w/e は残るが機密は載せない方針）。
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
# Kotlin の Logger.d / Logger.i も同様に副作用なしとみなして消させる。
-assumenosideeffects class app.anoterm.util.Logger {
    public void d(...);
    public void i(...);
}

# sshj uses reflection via slf4j and service loaders for crypto providers.
-keep class net.schmizz.sshj.** { *; }
-dontwarn net.schmizz.sshj.**
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.**
-dontwarn java.beans.**

# Tink / errorprone annotations (compile-time only).
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**

# Security-crypto / Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# androidx.biometric uses fragment reflection
-keep class androidx.biometric.** { *; }

# Room generated DAO impls
-keep class androidx.room.** { *; }
-keep class app.anoterm.data.db.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class app.anoterm.** {
  *** Companion;
}
-keepclasseswithmembers class app.anoterm.** {
  kotlinx.serialization.KSerializer serializer(...);
}
