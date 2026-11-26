# R8 rules for the Go Mode Android shell.

# Keep readable class, method, and line info in release logcat stack traces.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# LiteRT-LM ships no consumer ProGuard rules; its native JNI looks up classes and callbacks by name.
-keep class com.google.ai.edge.litertlm.** { *; }

# build.gradle.kts excludes jackson-dataformat-yaml; MCP tool schemas and arguments are JSON only.
-dontwarn com.fasterxml.jackson.dataformat.yaml.YAMLMapper
-dontwarn com.fasterxml.jackson.dataformat.yaml.YAMLMapper$Builder
