-keepattributes Signature,InnerClasses,EnclosingMethod
-dontwarn javax.annotation.**
# Optional Nimbus PEM/EC providers are not used; identity verification uses JCA RSA.
-dontwarn org.bouncycastle.**
# Compile-time annotations from Nimbus' shaded Gson; no runtime behavior.
-dontwarn com.google.errorprone.annotations.**
# Vosk's native entry points and JNA structures are resolved by name.
-keep class org.vosk.LibVosk { *; }
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-dontwarn java.awt.**
