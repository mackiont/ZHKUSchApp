# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class cn.edu.zhku.schedule.data.** {
    *** Companion;
}
-keepclasseswithmembers class cn.edu.zhku.schedule.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
