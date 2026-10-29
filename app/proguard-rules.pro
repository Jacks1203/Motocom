# Names stay readable: the record from a phone far away shows exception and class names.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Started by name with app_process over ADB (ShellLink), so nothing in the app calls it.
-keep class com.motocom.shell.ShellMain { public static void main(java.lang.String[]); }

# The Binder between the app and the shell process.
-keep class com.motocom.shell.IBikeDisplay { *; }
-keep class com.motocom.shell.IBikeDisplay$* { *; }
-keep class com.motocom.shell.IShellLog { *; }
-keep class com.motocom.shell.IShellLog$* { *; }

# Pairing with ADB: native code bound by name, and Conscrypt looked up by name in libadb.
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
-keep class org.conscrypt.OpenSSLProvider { <init>(); }
-keep class org.conscrypt.Conscrypt { public static *; }
