# Dot-source this before any .\gradlew.bat invocation in a fresh PowerShell:
#   . .\tools\env.ps1
#
# Why this exists: gradle.properties pins org.gradle.java.home for the Gradle
# *build* JVM, but gradlew.bat's own bootstrap still needs a JVM on
# JAVA_HOME/PATH before it can even read that file.
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_SDK_ROOT = "C:\Users\khans\AppData\Local\Android\Sdk"
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
$env:PATH = "$env:ANDROID_SDK_ROOT\platform-tools;$env:ANDROID_SDK_ROOT\cmdline-tools\latest\bin;$env:ANDROID_SDK_ROOT\emulator;$env:PATH"
