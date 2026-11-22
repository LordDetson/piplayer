package io.github.lorddetson.piplayer.ui.customization

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Xml
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan
import org.xmlpull.v1.XmlPullParser

internal const val SVG_ICON_MAX_BYTES = 10 * 1024

private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
private const val MAX_COORDINATE = 16_384f
private const val MAX_DEPTH = 8
private const val MAX_ELEMENTS = 96
private const val MAX_PATH_TOKENS = 2_048
private const val NUMBER = """[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"""
private val pathToken = Regex("""[AaCcHhLlMmQqSsTtVvZz]|$NUMBER""")
private val numberToken = Regex(NUMBER)
private val transformToken = Regex("""([A-Za-z]+)\(([^()]*)\)""")
private val documentDeclaration = Regex("""<!\s*(doctype|entity)\b""", RegexOption.IGNORE_CASE)
private val styleNames = setOf(
    "color", "fill", "fill-opacity", "fill-rule", "opacity", "stroke", "stroke-opacity", "stroke-width",
    "stroke-linecap", "stroke-linejoin", "stroke-miterlimit",
)
private val metadataNames = setOf("id", "role", "aria-hidden", "aria-label", "focusable")

/** Reads a bounded, self-contained icon. The source never reaches a WebView or a resource resolver. */
internal fun svgIconPainter(source: String, currentColor: Color = Color.Black): Painter? {
    if (source.length > SVG_ICON_MAX_BYTES || source.toByteArray(Charsets.UTF_8).size > SVG_ICON_MAX_BYTES ||
        documentDeclaration.containsMatchIn(source)
    ) return null
    return try {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(source.removePrefix("\uFEFF")))
        }
        StaticSvgReader(parser, currentColor.toArgb()).read()
    } catch (_: Exception) {
        null
    }
}

private class StaticSvgReader(private val parser: XmlPullParser, private val defaultColor: Int) {
    private var elements = 0

    fun read(): Painter {
        var event = parser.nextToken()
        while (isFormatting(event)) event = parser.nextToken()
        require(event == XmlPullParser.START_TAG && parser.name == "svg")
        val rootAttrs = attributes(setOf("viewBox", "height", "width", "version", "preserveAspectRatio"))
        val viewBox = rootAttrs["viewBox"]?.numbers() ?: listOf(
            0f, 0f, dimensions(rootAttrs, "width"), dimensions(rootAttrs, "height"),
        )
        require(viewBox.size == 4)
        val (x, y, width, height) = viewBox
        require(width >= 0.1f && height >= 0.1f && width / height in 0.001f..1_000f)
        val aspectRatio = rootAttrs["preserveAspectRatio"]?.trim()?.replace(Regex("\\s+"), " ")
        require(aspectRatio == null || aspectRatio in setOf("xMidYMid", "xMidYMid meet", "none"))

        val identity = Matrix()
        val rootTransform = rootAttrs["transform"].toTransform()
        val rootStyle = SvgStyle(foreground = defaultColor).with(rootAttrs)
        val root = SvgGroup(rootTransform, rootAttrs.alpha("opacity"), children(1, rootStyle, identity * rootTransform))
        require(root.hasPaths)
        event = parser.nextToken()
        while (isFormatting(event)) event = parser.nextToken()
        require(event == XmlPullParser.END_DOCUMENT)
        return StaticSvgPainter(x, y, width, height, aspectRatio == "none", root)
    }

    private fun children(depth: Int, style: SvgStyle, parentTransform: Matrix): List<SvgDrawing> {
        require(depth <= MAX_DEPTH)
        return buildList {
            while (true) {
                val event = parser.nextToken()
                when {
                    event == XmlPullParser.END_TAG -> break
                    isFormatting(event) -> Unit
                    event == XmlPullParser.START_TAG -> readElement(depth + 1, style, parentTransform)?.let(::add)
                    else -> error("Unsupported SVG content")
                }
            }
        }
    }

    private fun readElement(depth: Int, parentStyle: SvgStyle, parentTransform: Matrix): SvgDrawing? {
        val name = parser.name
        if (name == "title" || name == "desc") {
            attributes(emptySet(), styled = false)
            finishLeaf(textAllowed = true)
            return null
        }
        val geometry = when (name) {
            "g" -> emptySet()
            "path" -> setOf("d")
            "rect" -> setOf("x", "y", "width", "height", "rx", "ry")
            "circle" -> setOf("cx", "cy", "r")
            "ellipse" -> setOf("cx", "cy", "rx", "ry")
            "line" -> setOf("x1", "y1", "x2", "y2")
            "polyline", "polygon" -> setOf("points")
            else -> error("Unsupported SVG element")
        }
        val attrs = attributes(geometry)
        val transform = attrs["transform"].toTransform()
        val composed = parentTransform * transform
        val opacity = attrs.alpha("opacity")
        val style = parentStyle.with(attrs)
        if (name == "g") return SvgGroup(transform, opacity, children(depth, style, composed))

        val path = attrs.geometry(name)
        path.requireBoundedGeometry(composed)
        path.fillType = style.fillType
        finishLeaf()
        val fill = if (name == "line") null else style.fillPaint()
        return SvgShape(transform, opacity, path, fill, style.strokePaint())
    }

    private fun attributes(geometry: Set<String>, styled: Boolean = true): Map<String, String> {
        require(parser.namespace.orEmpty() in setOf("", SVG_NAMESPACE) && ++elements <= MAX_ELEMENTS)
        val attrs = mutableMapOf<String, String>()
        for (index in 0 until parser.attributeCount) {
            require(parser.getAttributeNamespace(index).orEmpty().isEmpty())
            val name = parser.getAttributeName(index)
            require(name in geometry || name in metadataNames || styled && (name in styleNames || name in setOf("style", "transform")))
            attrs[name] = parser.getAttributeValue(index)
        }
        attrs.remove("style")?.split(';')?.filter { it.isNotBlank() }?.forEach { declaration ->
            val parts = declaration.split(':', limit = 2)
            val name = parts[0].trim()
            require(parts.size == 2 && name in styleNames)
            attrs[name] = parts[1].trim()
        }
        return attrs
    }

    private fun finishLeaf(textAllowed: Boolean = false) {
        while (true) {
            val event = parser.nextToken()
            if (event == XmlPullParser.END_TAG) return
            require(isFormatting(event) || textAllowed && event == XmlPullParser.TEXT)
        }
    }

    private fun isFormatting(event: Int): Boolean = event == XmlPullParser.COMMENT ||
        event == XmlPullParser.IGNORABLE_WHITESPACE || event == XmlPullParser.TEXT && parser.text.isNullOrBlank()
}

private class StaticSvgPainter(
    private val x: Float,
    private val y: Float,
    private val width: Float,
    private val height: Float,
    private val stretch: Boolean,
    private val root: SvgDrawing,
) : Painter() {
    override val intrinsicSize = Size(width, height)

    override fun DrawScope.onDraw() {
        val fit = min(size.width / width, size.height / height)
        if (!fit.isFinite() || fit <= 0f) return
        val scaleX = if (stretch) size.width / width else fit
        val scaleY = if (stretch) size.height / height else fit
        drawIntoCanvas {
            val canvas = it.nativeCanvas
            val saved = canvas.save()
            try {
                canvas.clipRect(0f, 0f, size.width, size.height)
                canvas.translate((size.width - width * scaleX) / 2f, (size.height - height * scaleY) / 2f)
                canvas.scale(scaleX, scaleY)
                canvas.translate(-x, -y)
                root.draw(canvas)
            } finally {
                canvas.restoreToCount(saved)
            }
        }
    }
}

private sealed interface SvgDrawing {
    val hasPaths: Boolean
    fun draw(canvas: Canvas)
}

private class SvgGroup(private val transform: Matrix, private val opacity: Float, private val children: List<SvgDrawing>) : SvgDrawing {
    override val hasPaths: Boolean get() = children.any(SvgDrawing::hasPaths)
    override fun draw(canvas: Canvas) = canvas.withSvgTransform(transform, opacity) { children.forEach { it.draw(canvas) } }
}

private class SvgShape(
    private val transform: Matrix, private val opacity: Float, private val path: Path,
    private val fill: Paint?, private val stroke: Paint?,
) : SvgDrawing {
    override val hasPaths: Boolean get() = !path.isEmpty && (fill != null || stroke != null)
    override fun draw(canvas: Canvas) = canvas.withSvgTransform(transform, opacity) {
        fill?.let { canvas.drawPath(path, it) }
        stroke?.let { canvas.drawPath(path, it) }
    }
}

private inline fun Canvas.withSvgTransform(transform: Matrix, opacity: Float, draw: () -> Unit) {
    if (opacity == 0f) return
    val saved = save()
    try {
        concat(transform)
        if (opacity < 1f) saveLayerAlpha(null, (opacity * 255).roundToInt())
        draw()
    } finally {
        restoreToCount(saved)
    }
}

private data class SvgStyle(
    val foreground: Int,
    val fill: String = "black",
    val stroke: String = "none",
    val fillOpacity: Float = 1f,
    val strokeOpacity: Float = 1f,
    val fillType: Path.FillType = Path.FillType.WINDING,
    val strokeWidth: Float = 1f,
    val lineCap: Paint.Cap = Paint.Cap.BUTT,
    val lineJoin: Paint.Join = Paint.Join.MITER,
    val miterLimit: Float = 4f,
) {
    fun with(attrs: Map<String, String>): SvgStyle = copy(
        foreground = attrs["color"]?.takeUnless { it == "inherit" || it == "currentColor" }?.let(::solidColor) ?: foreground,
        fill = attrs["fill"].ink(fill),
        stroke = attrs["stroke"].ink(stroke),
        fillOpacity = attrs.alpha("fill-opacity", fillOpacity),
        strokeOpacity = attrs.alpha("stroke-opacity", strokeOpacity),
        fillType = when (attrs["fill-rule"]) {
            null, "inherit" -> fillType
            "nonzero" -> Path.FillType.WINDING
            "evenodd" -> Path.FillType.EVEN_ODD
            else -> error("Unsupported SVG fill rule")
        },
        strokeWidth = dimensions(attrs, "stroke-width", strokeWidth),
        lineCap = when (attrs["stroke-linecap"]) {
            null, "inherit" -> lineCap
            "butt" -> Paint.Cap.BUTT
            "round" -> Paint.Cap.ROUND
            "square" -> Paint.Cap.SQUARE
            else -> error("Unsupported SVG line cap")
        },
        lineJoin = when (attrs["stroke-linejoin"]) {
            null, "inherit" -> lineJoin
            "miter" -> Paint.Join.MITER
            "round" -> Paint.Join.ROUND
            "bevel" -> Paint.Join.BEVEL
            else -> error("Unsupported SVG line join")
        },
        miterLimit = dimensions(attrs, "stroke-miterlimit", miterLimit).also { require(it >= 1f) },
    )

    fun fillPaint(): Paint? = makePaint(fill, fillOpacity)
    fun strokePaint(): Paint? = if (strokeWidth == 0f) null else makePaint(stroke, strokeOpacity)?.apply {
        style = Paint.Style.STROKE
        strokeWidth = this@SvgStyle.strokeWidth
        strokeCap = lineCap
        strokeJoin = lineJoin
        strokeMiter = miterLimit
    }

    private fun makePaint(value: String, opacity: Float): Paint? {
        if (value == "none") return null
        val colorValue = if (value == "currentColor") foreground else solidColor(value)
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorValue
            alpha = (AndroidColor.alpha(colorValue) * opacity).roundToInt()
        }
    }
}

private fun String?.ink(default: String): String {
    val value = this?.trim()?.takeUnless { it == "inherit" } ?: return default
    if (value != "none" && value != "currentColor") solidColor(value)
    return value
}

private fun Map<String, String>.alpha(key: String, default: Float = 1f): Float {
    val value = get(key)?.trim()?.takeUnless { it == "inherit" } ?: return default
    val ratio = if (value.endsWith("%")) value.dropLast(1).coordinate() / 100f else value.coordinate()
    require(ratio in 0f..1f)
    return ratio
}

private fun solidColor(value: String): Int {
    val text = value.trim()
    if (text == "transparent") return AndroidColor.TRANSPARENT
    if (text == "green") return 0xff008000.toInt()
    if (text.startsWith("rgb(") && text.endsWith(')')) {
        val channels = text.substring(4, text.length - 1).numbers()
        require(channels.size == 3 && channels.all { it in 0f..255f })
        return AndroidColor.rgb(channels[0].roundToInt(), channels[1].roundToInt(), channels[2].roundToInt())
    }
    if (!text.startsWith('#')) return AndroidColor.parseColor(text)
    val rgba = text.substring(1)
    return when (rgba.length) {
        3, 4 -> {
            val channels = rgba.map { it.digitToInt(16) * 17 }
            AndroidColor.argb(channels.getOrElse(3) { 255 }, channels[0], channels[1], channels[2])
        }
        6 -> rgba.toInt(16) or 0xff000000.toInt()
        8 -> rgba.toLong(16).let { ((it and 0xff) shl 24 or (it ushr 8)).toInt() }
        else -> error("Unsupported SVG color")
    }
}

private fun Map<String, String>.geometry(name: String): Path {
    fun number(key: String) = get(key)?.trim()?.removeSuffix("px")?.coordinate() ?: 0f
    fun radius(key: String) = dimensions(this, key, 0f)
    return when (name) {
        "path" -> PathParser().parsePathString(getValue("d").checkedPath()).toPath().asAndroidPath()
        "rect" -> {
            val x = number("x")
            val y = number("y")
            val width = dimensions(this, "width")
            val height = dimensions(this, "height")
            val rx = min(radius(if ("rx" in this) "rx" else "ry"), width / 2f)
            val ry = min(radius(if ("ry" in this) "ry" else "rx"), height / 2f)
            Path().apply { addRoundRect(RectF(x, y, x + width, y + height), rx, ry, Path.Direction.CW) }
        }
        "circle", "ellipse" -> {
            val x = number("cx")
            val y = number("cy")
            val rx = if (name == "circle") radius("r") else radius("rx")
            val ry = if (name == "circle") rx else radius("ry")
            Path().apply { addOval(x - rx, y - ry, x + rx, y + ry, Path.Direction.CW) }
        }
        "line" -> Path().apply {
            moveTo(number("x1"), number("y1"))
            lineTo(number("x2"), number("y2"))
        }
        else -> {
            val points = getValue("points").numbers()
            require(points.size in 4..MAX_PATH_TOKENS && points.size % 2 == 0)
            Path().apply {
                moveTo(points[0], points[1])
                for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
                if (name == "polygon") close()
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun Path.requireBoundedGeometry(transform: Matrix) {
    val bounds = RectF()
    computeBounds(bounds, false)
    transform.mapRect(bounds)
    require(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        .all { it.isFinite() && abs(it) <= 8f * MAX_COORDINATE })
}

private fun dimensions(attrs: Map<String, String>, name: String, default: Float? = null): Float =
    (attrs[name]?.trim()?.removeSuffix("px")?.coordinate() ?: requireNotNull(default)).also { require(it >= 0f) }

private fun String.coordinate(): Float {
    require(numberToken.matches(this))
    return toFloat().also { require(it.isFinite() && abs(it) <= MAX_COORDINATE) }
}

private fun String.tokens(pattern: Regex): List<String> {
    var previous = 0
    return pattern.findAll(this).map { match ->
        require(substring(previous, match.range.first).all { it.isWhitespace() || it == ',' })
        previous = match.range.last + 1
        match.value
    }.toList().also { require(substring(previous).all { it.isWhitespace() || it == ',' }) }
}

private fun String.numbers(): List<Float> = tokens(numberToken).map(String::coordinate)

private fun String.checkedPath(): String {
    val tokens = tokens(pathToken)
    require(tokens.isNotEmpty() && tokens.size <= MAX_PATH_TOKENS && tokens.first() in setOf("M", "m"))
    var index = 0
    while (index < tokens.size) {
        val command = tokens[index++].single().uppercaseChar()
        val arity = when (command) {
            'Z' -> 0
            'H', 'V' -> 1
            'M', 'L', 'T' -> 2
            'Q', 'S' -> 4
            'C' -> 6
            'A' -> 7
            else -> error("Unsupported SVG path command")
        }
        val start = index
        while (index < tokens.size && numberToken.matches(tokens[index])) {
            val value = tokens[index].coordinate()
            if (command == 'A') when ((index - start) % 7) {
                0, 1 -> require(value >= 0f)
                3, 4 -> require(value == 0f || value == 1f)
            }
            index++
        }
        val count = index - start
        require(if (arity == 0) count == 0 else count >= arity && count % arity == 0)
    }
    return tokens.joinToString(" ")
}

private fun String?.toTransform(): Matrix {
    var combined = Matrix()
    if (isNullOrBlank()) return combined
    val transforms = tokens(transformToken)
    require(transforms.size in 1..16)
    for (entry in transforms) {
        val match = transformToken.matchEntire(entry) ?: error("Invalid SVG transform")
        val args = match.groupValues[2].numbers()
        val operation = Matrix()
        when (match.groupValues[1]) {
            "matrix" -> {
                require(args.size == 6)
                operation.setValues(floatArrayOf(args[0], args[2], args[4], args[1], args[3], args[5], 0f, 0f, 1f))
            }
            "translate" -> {
                require(args.size in 1..2)
                operation.setTranslate(args[0], args.getOrElse(1) { 0f })
            }
            "scale" -> {
                require(args.size in 1..2)
                operation.setScale(args[0], args.getOrElse(1) { args[0] })
            }
            "rotate" -> {
                require(args.size == 1 || args.size == 3)
                operation.setRotate(args[0], args.getOrElse(1) { 0f }, args.getOrElse(2) { 0f })
            }
            "skewX", "skewY" -> {
                require(args.size == 1)
                val factor = tan(Math.toRadians(args[0].toDouble())).toFloat()
                if (match.groupValues[1] == "skewX") operation.setSkew(factor, 0f) else operation.setSkew(0f, factor)
            }
            else -> error("Unsupported SVG transform")
        }
        combined = combined * operation
    }
    return combined
}

private operator fun Matrix.times(other: Matrix): Matrix = Matrix().apply {
    setConcat(this@times, other)
    val values = FloatArray(9)
    getValues(values)
    require(values.all { it.isFinite() && abs(it) <= MAX_COORDINATE } &&
        listOf(values[0], values[1], values[3], values[4]).all { abs(it) <= 1_024f })
}
