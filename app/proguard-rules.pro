# R8 shrinks and obfuscates the release build. Rules are intentionally minimal.
-keep class com.smartmanager.whatsappshoot.platform.WhatsAppAccessibilityService { *; }
-keep class com.smartmanager.whatsappshoot.platform.ProjectionService { *; }
-keep class com.smartmanager.whatsappshoot.platform.ProjectionPermissionActivity { *; }
-keep class com.smartmanager.whatsappshoot.platform.AttachTrampolineActivity { *; }
-keep class com.smartmanager.whatsappshoot.platform.ActionReceiver { *; }
-keep class com.smartmanager.whatsappshoot.App { *; }
# Drop log calls from release builds.
-assumenosideeffects class android.util.Log { *; }
# Tesseract4Android: native code reads and calls back into these classes by name.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
