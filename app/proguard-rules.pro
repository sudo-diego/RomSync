# Shizuku: newProcess se llama por reflexión.
-keep class rikka.shizuku.Shizuku { *; }
-keepclassmembers enum * { *; }
# JSch carga sus algoritmos por nombre.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**
