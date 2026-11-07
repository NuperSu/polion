# WorkManager creates workers by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
# WorkManager's Room database is instantiated reflectively.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
