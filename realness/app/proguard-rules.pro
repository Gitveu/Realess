# Keep AndroidViewModel constructors used via reflection by ViewModelProvider
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    public <init>(android.app.Application);
}

# Keep Kotlin / Compose metadata & annotations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Suppress benign coroutines / platform warnings
-dontwarn kotlinx.coroutines.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
