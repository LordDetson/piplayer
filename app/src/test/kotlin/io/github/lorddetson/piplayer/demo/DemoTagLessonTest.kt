package io.github.lorddetson.piplayer.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DemoTagLessonTest {
    @Test
    fun `previewing an example match can be cancelled without changing the demo tag`() {
        val preview = DemoTagLesson.ORIGINAL.preview()
        assertThat(preview).isEqualTo(DemoTagLesson.PREVIEW)
        assertThat(preview.cancel()).isEqualTo(DemoTagLesson.ORIGINAL)
    }

    @Test
    fun `a preview must precede apply and undo returns to the original tag`() {
        assertThat(DemoTagLesson.ORIGINAL.apply()).isEqualTo(DemoTagLesson.ORIGINAL)
        val applied = DemoTagLesson.ORIGINAL.preview().apply()
        assertThat(applied).isEqualTo(DemoTagLesson.APPLIED)
        assertThat(applied.undo()).isEqualTo(DemoTagLesson.ORIGINAL)
    }
}
