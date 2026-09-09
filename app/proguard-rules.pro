-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.eslee.llmusage.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
