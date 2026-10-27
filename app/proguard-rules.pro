# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# The native solver's entry points are resolved by name: libwfc.so exports
# Java_ca_justinmo_wfcbitmap_WfcNative_modelCreate and friends, so neither the class nor its native
# methods may be renamed. WfcNative is annotated @Keep as well; this rule is the belt to that
# suspenders, in case the annotation is ever dropped by mistake.
-keep class ca.justinmo.wfcbitmap.WfcNative {
    native <methods>;
}