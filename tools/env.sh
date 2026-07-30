#!/usr/bin/env bash
# Source this before any ./gradlew invocation in a fresh Bash shell:
#   source tools/env.sh
#
# Why this exists: gradle.properties pins org.gradle.java.home for the Gradle
# *build* JVM, but gradlew's own bootstrap script still needs a JVM on
# JAVA_HOME/PATH before it can even read that file. A user-level JAVA_HOME
# (setx) does not reach shells already spawned in this session's process tree,
# so don't rely on it — source this file explicitly in every new shell.
export JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
