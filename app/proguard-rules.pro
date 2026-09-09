# Pict · R8/ProGuard 规则

# 保留行号，便于崩溃栈还原
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 元数据读取依赖反射，保留其公开 API
-keep class com.drew.** { *; }
-keep class org.apache.commons.imaging.** { *; }
-keep class com.adobe.xmp.** { *; }
-keep class androidx.exifinterface.** { *; }

# Kotlin 元数据
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
