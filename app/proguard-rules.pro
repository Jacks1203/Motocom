# Proguard / R8 rules for Motocom

# Crucial: com.motocom.shell is executed out-of-process via ADB 'app_process'
-keep class com.motocom.shell.** { *; }
-keepclassmembers class com.motocom.shell.ShellMain {
    public static void main(java.lang.String[]);
}

# AIDL interfaces and generated Binder stubs
-keep interface com.motocom.shell.IBikeDisplay { *; }
-keep class com.motocom.shell.IBikeDisplay$* { *; }
-keep interface com.motocom.shell.IShellLog { *; }
-keep class com.motocom.shell.IShellLog$* { *; }

# Security and Crypto libraries (BouncyCastle & Conscrypt use reflection for JCE providers)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# libadb
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn io.github.muntashirakon.adb.**

# ZXing QR scanning
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.journeyapps.barcodescanner.**
-dontwarn com.google.zxing.**

# Hidden / Internal Android framework APIs used via reflection in DisplayService & ShellMain
-dontwarn android.companion.virtual.**
-dontwarn android.hardware.display.**
-dontwarn android.app.**
