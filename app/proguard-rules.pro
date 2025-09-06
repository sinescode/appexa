# Keep main activity
-keep class com.example.minimalapp.MainActivity { *; }
# Keep Apache POI classes
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class schemasMicrosoftComVml.** { *; }

# Keep log4j classes (used by POI)
-keep class org.apache.logging.log4j.** { *; }