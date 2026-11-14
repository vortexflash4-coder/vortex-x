# VORTEX X release shrinking rules.
# Media3, Room, Hilt and kotlinx.serialization ship consumer rules with their AARs.

# Keep serializable navigation routes (type-safe navigation reflects on them).
-keep @kotlinx.serialization.Serializable class com.vortexx.player.ui.navigation.** { *; }
-keepclassmembers class com.vortexx.player.ui.navigation.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
