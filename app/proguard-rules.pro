# Ktor + Netty on Android. These keep the embedded server working under R8.
-dontwarn io.netty.**
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**
-dontwarn org.bouncycastle.**
-keep class io.netty.** { *; }
-keepclassmembers class io.netty.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class net.clickarr.**$$serializer { *; }
-keepclassmembers class net.clickarr.** { *** Companion; }
-keepclasseswithmembers class net.clickarr.** { kotlinx.serialization.KSerializer serializer(...); }
