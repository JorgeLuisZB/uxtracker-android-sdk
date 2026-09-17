# The SDK uses no reflection; only its public API must survive shrinking in host apps.
-keep public class com.wzagroup.uxtracker.UxTracker { public *; }
-keep public class com.wzagroup.uxtracker.UxTrackerConfig { public *; }
-keep public class com.wzagroup.uxtracker.UxTrackerConfig$Builder { public *; }
