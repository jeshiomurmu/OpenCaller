# R8 rules for OpenCaller's release builds (docs/phase.md issue 59). Most libraries ship their own rules: Retrofit,
# kotlinx.serialization (@Serializable API models and navigation routes), Room, Hilt, WorkManager, SQLCipher's JNI,
# LiveKit's WebRTC JNI and protobuf, OkHttp. Only what they don't cover is here.

# Readable stack traces: each release publishes its mapping file (release.yml), which restores names and lines.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# DataStore's protobuf-lite messages (core:datastore): the protobuf runtime finds their fields by name.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
