# Polaris File keep rules
-keep class com.limao.unzip.worker.** { *; }
-keep class com.limao.unzip.selftest.** { *; }
# JNI 按名反射回调（onEntry/onEntryStart/...）——类与成员必须原名保留
-keep class com.limao.unzip.native.** { *; }
-keepclassmembers class com.limao.unzip.files.** { *; }
