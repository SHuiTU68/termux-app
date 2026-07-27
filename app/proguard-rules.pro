# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# Shizuku integration
# ---------------------------------------------------------------------------
# ShizukuCli is launched via `app_process` from the $PREFIX/bin/shizuku wrapper
# script using the Termux APK as -Djava.class.path. The compiler cannot see
# this reference, so without an explicit -keep the class would be removed by
# R8 shrinking in release builds.
-keep class com.termux.app.shizuku.** { *; }

# Shizuku API client, ContentProvider, AIDL stubs and parcelables. These are
# referenced reflectively by the Shizuku server (a separate process) and by
# our CLI when run via app_process, so they must be kept verbatim.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }

# AIDL-generated IShizukuService / IRemoteProcess stubs include a static
# CREATOR field used by Android's Parcel machinery. Keep all Parcelable
# CREATOR fields defensively.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

