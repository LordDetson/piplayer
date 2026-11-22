package io.github.lorddetson.piplayer.ui.customization

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSvgIconTest {
    @Test
    fun drawsSolidGeometryInsideTheViewBoxWithInheritedStyleAndTransform() {
        val painter = checkNotNull(svgIconPainter(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <g transform="translate(4 4)" fill="#f00">
                  <path d="M0 0h8v8H0z"/>
                </g>
              </svg>""",
        ))
        assertEquals(Size(24f, 24f), painter.intrinsicSize)
        val image = render(painter)
        assertEquals(android.graphics.Color.RED, image.getPixel(8, 8))
        assertEquals(android.graphics.Color.TRANSPARENT, image.getPixel(2, 2))
        image.recycle()
    }

    @Test
    fun resolvesCurrentColorToTheControlTint() {
        val painter = checkNotNull(svgIconPainter(
            """<svg width="24" height="24"><circle cx="12" cy="12" r="6" fill="currentColor"/></svg>""",
            currentColor = Color.Blue,
        ))
        val image = render(painter)
        assertEquals(android.graphics.Color.BLUE, image.getPixel(12, 12))
        image.recycle()
    }

    @Test
    fun rejectsXmlDeclarationsOfDocumentTypesReferencesAndUnsupportedElements() {
        assertNull(svgIconPainter("<!DOCTYPE svg>" + icon("<circle r='4'/>")))
        assertNull(svgIconPainter(icon("<script/>")))
        assertNull(svgIconPainter(icon("<image href='https://example.invalid/icon.png'/>")))
        assertNull(svgIconPainter(icon("<path d='M1 1h2' stroke='url(#paint)'/>")))
        assertNull(svgIconPainter(icon("<path d='M1 1h2' onclick='ignored'/>")))
    }

    @Test
    fun rejectsOversizedInvalidOrUnboundedGeometry() {
        assertNull(svgIconPainter(icon("<title>${"ё".repeat(SVG_ICON_MAX_BYTES)}</title>")))
        assertNull(svgIconPainter(icon("<path d='M1'/>")))
        assertNull(svgIconPainter(icon("<path d='M1e30 1 h2'/>")))
        assertNull(svgIconPainter("<svg viewBox='0 0 0 24'><circle r='4'/></svg>"))
        assertNotNull(svgIconPainter(icon("<path d='M1 1 L12 12' fill='none' stroke='black'/>")))
    }

    private fun icon(contents: String) = "<svg viewBox='0 0 24 24'>$contents</svg>"

    private fun render(painter: Painter): Bitmap {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(android.graphics.Canvas(bitmap)), Size(24f, 24f)) {
            with(painter) { draw(size) }
        }
        return bitmap
    }
}
