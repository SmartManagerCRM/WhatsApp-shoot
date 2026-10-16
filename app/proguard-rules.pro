# R8 shrinks and obfuscates the release build. Rules are intentionally minimal.
-keep class com.smartmanager.whatsappshoot.platform.WhatsAppAccessibilityService { *; }
-keep class com.smartmanager.whatsappshoot.platform.ProjectionService { *; }
-keep class com.smartmanager.whatsappshoot.platform.ProjectionPermissionActivity { *; }
-keep class com.smartmanager.whatsappshoot.platform.AttachTrampolineActivity { *; }
-keep class com.smartmanager.whatsappshoot.platform.ActionReceiver { *; }
-keep class com.smartmanager.whatsappshoot.App { *; }
# Drop log calls from release builds.
-assumenosideeffects class android.util.Log { *; }
