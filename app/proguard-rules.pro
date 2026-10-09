# Ktor CIO server, SLF4J, BouncyCastle (spike only) under R8.
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.jcajce.provider.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class net.clickarr.**$$serializer { *; }
-keepclassmembers class net.clickarr.** { *** Companion; }
-keepclasseswithmembers class net.clickarr.** { kotlinx.serialization.KSerializer serializer(...); }
