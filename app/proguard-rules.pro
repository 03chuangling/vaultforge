# VaultForge ProGuard 规则（v0.1 未启用混淆）
-dontwarn org.bouncycastle.**
-dontwarn com.jcraft.jsch.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.vaultforge.app.** { *; }
