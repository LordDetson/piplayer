package io.github.lorddetson.piplayer.demo

internal enum class DemoTagLesson {
    ORIGINAL, PREVIEW, APPLIED;

    fun preview(): DemoTagLesson = if (this == ORIGINAL) PREVIEW else this

    fun cancel(): DemoTagLesson = if (this == PREVIEW) ORIGINAL else this

    fun apply(): DemoTagLesson = if (this == PREVIEW) APPLIED else this

    fun undo(): DemoTagLesson = if (this == APPLIED) ORIGINAL else this
}
