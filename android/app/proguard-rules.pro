# JNA binds native functions and structures by reflection.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# UniFFI bindings: JNA looks up the generated interface methods and callback classes by name.
-keep class ai.storyteller.photocraft.engine.** { *; }
