# Preserve the native app's audited class identities and platform/JNI entry points.
# Shrinking removes unused AndroidX compatibility modules pulled in by LibVLC's
# legacy-support dependency, including compatibility-only WebView references.
-dontobfuscate
-keep class se.automatiziraj.termodom.** { *; }
-keep class org.videolan.libvlc.** { *; }
