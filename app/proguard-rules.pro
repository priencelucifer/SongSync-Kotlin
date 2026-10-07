# NewPipeExtractor parses YouTube responses partly via reflection and runs YouTube's player
# JavaScript through Rhino, so neither can be shrunk safely.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.jspecify.annotations.**
