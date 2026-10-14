// Root build file — plugin versions mirror the proven setup used across ~/dev
// AGP 9.4.1: built-in Kotlin support, so the separate org.jetbrains.kotlin.android
// plugin is gone (AGP compiles Kotlin itself). Parcelize is still applied explicitly
// because it is a compiler plugin, not part of built-in Kotlin.
plugins {
    id("com.android.application")      version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.4.21" apply false
}