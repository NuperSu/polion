# WorkManager creates workers by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
# WorkManager's Room database is instantiated reflectively.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# bitcoin-kmp loads libsecp256k1 through JNI; native methods bind to these class and method names.
-keep class fr.acinq.secp256k1.** { *; }
