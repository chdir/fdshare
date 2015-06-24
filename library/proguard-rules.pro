-verbose
-dontpreverify

-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,EnclosingMethod,Exceptions

-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclassmembers
-dontskipnonpubliclibraryclasses

-obfuscationdictionary proguard-dict.txt

-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable
-optimizationpasses 5

-useuniqueclassmembernames

-keepclassmembers class **.R$* {
    public static <fields>;
}

-keep public class !net.sf.fdshare.BuildConfig,** {
    public *;
}

# Retrolambda, per https://github.com/evant/gradle-retrolambda
-dontwarn java.lang.invoke.*