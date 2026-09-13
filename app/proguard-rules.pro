# Pict · R8/ProGuard 规则

# 保留行号，便于崩溃栈还原
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 元数据读取依赖反射，保留其公开 API
-keep class com.drew.** { *; }
-keep class org.apache.commons.imaging.** { *; }
-keep class com.adobe.xmp.** { *; }
-keep class androidx.exifinterface.** { *; }

# commons-imaging 的解析路径引用桌面 JVM 才有的 AWT/ImageIO 类（java.awt.*、javax.imageio.*），
# Android 上不存在。R8 在 release 构建里把「缺失类」当错误终止（minifyReleaseWithR8 失败），
# 必须逐包抑制；App 自身的 TIFF/JPEG 读写路径不触碰这些类。
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.apache.commons.imaging.**

# Kotlin 元数据
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
