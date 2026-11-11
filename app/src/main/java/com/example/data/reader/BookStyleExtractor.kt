package com.example.data.reader

import com.example.domain.reader.BlockAlign
import com.example.domain.reader.EmbeddedFontFace
import com.example.domain.reader.ParagraphStyleInfo
import com.example.domain.reader.StyledRun
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.IdentityHashMap

/** True when [raw] looks like (X)HTML rather than plain text. Shared with the sentence tokenizer. */
internal fun looksLikeHtml(raw: String): Boolean =
    raw.contains("<html", ignoreCase = true) ||
        raw.contains("<body", ignoreCase = true) ||
        raw.contains("<p", ignoreCase = true) ||
        raw.contains("<div", ignoreCase = true)

private val CONTAINER_TAGS = setOf("p", "li", "blockquote", "h1", "h2", "h3")

/**
 * The block elements that become reader paragraphs, in order, with their text. The sentence
 * tokenizer and the style extractor both use this, so paragraph indices always line up.
 */
internal fun readableBlocks(doc: Document): List<Pair<Element, String>> {
    val body = doc.body() ?: return emptyList()
    val elements = body.select("h1, h2, h3, h4, h5, h6, p, li, blockquote")
    if (elements.isEmpty()) {
        val text = body.text().trim()
        return if (text.isEmpty()) emptyList() else listOf(body to text)
    }
    return elements
        .filter { el -> el.children().none { it.tagName() in CONTAINER_TAGS } }
        .map { it to it.text().trim() }
        .filter { it.second.isNotEmpty() }
}

/** A parsed `@font-face` rule. */
data class CssFontFace(val family: String, val src: String, val weight: Int, val italic: Boolean)

/** A parsed stylesheet: plain rules (in file order) and font faces. */
class ParsedCss(val rules: List<Pair<String, Map<String, String>>>, val fontFaces: List<CssFontFace>)

object CssParser {
    private val comments = Regex("/\\*[\\s\\S]*?\\*/")

    fun parse(css: String): ParsedCss {
        val text = comments.replace(css, " ")
        val rules = ArrayList<Pair<String, Map<String, String>>>()
        val faces = ArrayList<CssFontFace>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && (text[i].isWhitespace() || text[i] == '}')) i++
            if (i >= n) break
            val open = text.indexOf('{', i)
            if (text[i] == '@') {
                val semi = text.indexOf(';', i)
                if (open < 0 || (semi in 0 until open)) {
                    // @charset / @import / @namespace
                    i = if (semi < 0) n else semi + 1
                    continue
                }
                val name = text.substring(i, open).trim().lowercase()
                val close = matchingBrace(text, open)
                val body = text.substring(open + 1, close.coerceAtMost(n))
                if (name.startsWith("@font-face")) {
                    parseFontFace(parseDeclarations(body))?.let { faces.add(it) }
                } else if (name.startsWith("@media") && (name.contains("screen") || name.contains("all")) &&
                    !name.contains("amzn")
                ) {
                    val inner = parse(body)
                    rules.addAll(inner.rules)
                    faces.addAll(inner.fontFaces)
                }
                i = close + 1
                continue
            }
            if (open < 0) break
            val close = text.indexOf('}', open).let { if (it < 0) n else it }
            val selectors = text.substring(i, open).trim()
            val decls = parseDeclarations(text.substring(open + 1, close))
            if (selectors.isNotEmpty() && decls.isNotEmpty()) rules.add(selectors to decls)
            i = close + 1
        }
        return ParsedCss(rules, faces)
    }

    private fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        for (k in open until text.length) {
            when (text[k]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return k
            }
        }
        return text.length
    }

    fun parseDeclarations(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in body.split(';')) {
            val colon = part.indexOf(':')
            if (colon <= 0) continue
            val key = part.substring(0, colon).trim().lowercase()
            val value = part.substring(colon + 1).replace("!important", "", ignoreCase = true).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun parseFontFace(d: Map<String, String>): CssFontFace? {
        val family = d["font-family"]?.trim()?.trim('"', '\'')?.takeIf { it.isNotBlank() } ?: return null
        val src = d["src"] ?: return null
        val url = Regex("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)").find(src)?.groupValues?.get(1) ?: return null
        if (url.startsWith("data:")) return null
        return CssFontFace(family, url, parseWeight(d["font-weight"]) ?: 400, d["font-style"]?.contains("italic") == true || d["font-style"]?.contains("oblique") == true)
    }

    fun parseWeight(v: String?): Int? = when (val s = v?.trim()?.lowercase()) {
        null -> null
        "bold", "bolder" -> 700
        "normal" -> 400
        "lighter" -> 300
        else -> s.toIntOrNull()
    }

    /** CSS specificity packed into one Int (ids, classes/attributes/pseudo-classes, tags). */
    fun specificity(selector: String): Int {
        val ids = selector.count { it == '#' }
        val classes = selector.count { it == '.' || it == '[' || it == ':' }
        val tags = selector.split(Regex("[\\s>+~]+")).count { it.isNotEmpty() && it[0].isLetter() }
        return ids * 10_000 + classes * 100 + tags
    }
}

/** Computes per-paragraph styling for one chapter from its HTML and the book's stylesheets. */
object BookStyleExtractor {

    private val unsupportedPseudo = Regex(":(:|before|after|first-letter|first-line|hover|active|focus|visited|link|selection)")

    private data class Computed(
        val italic: Boolean = false,
        val bold: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val smallCaps: Boolean = false,
        val script: Int = 0,
        val family: String? = null,
        val align: BlockAlign = BlockAlign.DEFAULT,
        val indentEm: Float = 0f,
        val sizeScale: Float = 1f
    )

    /**
     * @param stylesheets the book's CSS files by ZIP path
     * @param chapterPath ZIP path of this chapter (to resolve `<link href>`)
     * @param fontFamilies lower-cased names of the embedded font families available
     */
    fun extract(
        rawHtml: String,
        chapterPath: String,
        stylesheets: Map<String, String>,
        fontFamilies: Set<String>,
        resolvePath: (baseDir: String, href: String) -> String
    ): List<ParagraphStyleInfo> {
        if (!looksLikeHtml(rawHtml)) return emptyList()
        val doc = Jsoup.parse(rawHtml)

        // Gather the chapter's CSS (linked sheets in order, then inline <style> blocks)
        val chapterDir = chapterPath.substringBeforeLast('/', "")
        val cssTexts = ArrayList<String>()
        for (el in doc.select("link[href], style")) {
            if (el.tagName() == "style") {
                cssTexts.add(el.data())
            } else if (el.attr("rel").contains("stylesheet", true) || el.attr("href").endsWith(".css", true)) {
                stylesheets[resolvePath(chapterDir, el.attr("href"))]?.let { cssTexts.add(it) }
            }
        }
        doc.select("script, style, nav, head").remove()

        val declared = IdentityHashMap<Element, HashMap<String, String>>()
        var order = 0
        val rules = cssTexts.flatMap { CssParser.parse(it).rules }.flatMap { (selectors, decls) ->
            selectors.split(',').map { it.trim() }.filter { it.isNotEmpty() && !unsupportedPseudo.containsMatchIn(it) }
                .map { Triple(it, decls, order++) }
        }.sortedWith(compareBy({ CssParser.specificity(it.first) }, { it.third }))
        for ((selector, decls) in rules) {
            val matched = runCatching { doc.select(selector) }.getOrNull() ?: continue
            for (el in matched) declared.getOrPut(el) { HashMap() }.putAll(decls)
        }
        for (el in doc.select("[style]")) {
            declared.getOrPut(el) { HashMap() }.putAll(CssParser.parseDeclarations(el.attr("style")))
        }

        val memo = IdentityHashMap<Element, Computed>()
        fun computed(el: Element): Computed {
            memo[el]?.let { return it }
            val parent = el.parent()
            var c = if (parent == null || parent is Document) Computed() else computed(parent)
            c = c.copy(script = 0) // vertical-align isn't inherited
            c = applyTag(el, c)
            declared[el]?.let { c = applyDeclarations(it, c, fontFamilies) }
            memo[el] = c
            return c
        }

        return readableBlocks(doc).map { (block, target) ->
            val base = computed(block)
            val src = StringBuilder()
            val rawRuns = ArrayList<Triple<Int, Int, Computed>>()
            var lastSpace = true
            fun append(text: String, style: Computed) {
                val start = src.length
                for (ch in text) {
                    if (ch.isWhitespace()) {
                        if (!lastSpace) {
                            src.append(' ')
                            lastSpace = true
                        }
                    } else {
                        src.append(ch)
                        lastSpace = false
                    }
                }
                if (src.length > start) rawRuns.add(Triple(start, src.length, style))
            }
            fun walk(node: Node) {
                when (node) {
                    is TextNode -> (node.parent() as? Element)?.let { append(node.wholeText, computed(it)) }
                    is Element -> if (node.tagName() == "br") {
                        append(" ", base)
                    } else {
                        val blockish = node.isBlock && node !== block
                        if (blockish) append(" ", base)
                        node.childNodes().forEach(::walk)
                        if (blockish) append(" ", base)
                    }
                }
            }
            walk(block)

            val map = alignOffsets(src.toString(), target)
            val runs = ArrayList<StyledRun>()
            for ((a, b, s) in rawRuns) {
                val family = s.family?.takeIf { it != base.family }
                if (!s.italic && !s.bold && !s.underline && !s.strike && !s.smallCaps && s.script == 0 && family == null) continue
                val start = map[a].coerceIn(0, target.length)
                val end = (map[b - 1] + 1).coerceIn(start, target.length)
                if (end <= start) continue
                val run = StyledRun(start, end, s.italic, s.bold, s.underline, s.strike, s.smallCaps, s.script, family)
                val prev = runs.lastOrNull()
                if (prev != null && prev.copy(start = 0, end = 0) == run.copy(start = 0, end = 0) && run.start - prev.end <= 1) {
                    runs[runs.size - 1] = prev.copy(end = run.end)
                } else {
                    runs.add(run)
                }
            }
            val headingLevel = block.tagName().let { t -> if (t.length == 2 && t[0] == 'h' && t[1].isDigit()) t[1].digitToInt() else 0 }
            ParagraphStyleInfo(
                align = base.align,
                indentEm = base.indentEm,
                fontFamily = base.family,
                sizeScale = base.sizeScale.coerceIn(0.6f, 2.4f),
                headingLevel = headingLevel,
                runs = runs
            )
        }
    }

    private fun applyTag(el: Element, c: Computed): Computed {
        var r = c
        when (el.tagName()) {
            "i", "em", "cite", "dfn", "var", "address" -> r = r.copy(italic = true)
            "b", "strong", "th", "h1", "h2", "h3", "h4", "h5", "h6" -> r = r.copy(bold = true)
            "u", "ins" -> r = r.copy(underline = true)
            "s", "strike", "del" -> r = r.copy(strike = true)
            "sup" -> r = r.copy(script = 1)
            "sub" -> r = r.copy(script = -1)
            "center" -> r = r.copy(align = BlockAlign.CENTER)
            "big" -> r = r.copy(sizeScale = r.sizeScale * 1.2f)
            "small" -> r = r.copy(sizeScale = r.sizeScale * 0.85f)
        }
        alignOf(el.attr("align"))?.let { r = r.copy(align = it) }
        return r
    }

    private fun alignOf(v: String?): BlockAlign? = when (v?.trim()?.lowercase()) {
        "center", "-webkit-center", "middle" -> BlockAlign.CENTER
        "right", "end" -> BlockAlign.END
        "left", "start" -> BlockAlign.START
        "justify" -> BlockAlign.JUSTIFY
        else -> null
    }

    private fun applyDeclarations(d: Map<String, String>, c: Computed, fontFamilies: Set<String>): Computed {
        var r = c
        d["font-style"]?.lowercase()?.let { r = r.copy(italic = it.contains("italic") || it.contains("oblique")) }
        CssParser.parseWeight(d["font-weight"])?.let { r = r.copy(bold = it >= 600) }
        (d["text-decoration"] ?: d["text-decoration-line"])?.lowercase()?.let {
            if (it.contains("underline")) r = r.copy(underline = true)
            if (it.contains("line-through")) r = r.copy(strike = true)
        }
        (d["font-variant"] ?: d["font-variant-caps"])?.lowercase()?.let { r = r.copy(smallCaps = it.contains("small-caps")) }
        d["vertical-align"]?.lowercase()?.let {
            r = r.copy(script = when (it) { "super" -> 1; "sub" -> -1; else -> r.script })
        }
        d["font-family"]?.let { list ->
            list.split(',').map { it.trim().trim('"', '\'') }
                .firstOrNull { it.lowercase() in fontFamilies }
                ?.let { r = r.copy(family = it.lowercase()) }
        }
        alignOf(d["text-align"])?.let { r = r.copy(align = it) }
        d["text-indent"]?.let { v -> lengthEm(v, percentToEm = 0.33f)?.let { r = r.copy(indentEm = it.coerceIn(0f, 4f)) } }
        d["font-size"]?.let { v -> fontScale(v, r.sizeScale)?.let { r = r.copy(sizeScale = it) } }
        return r
    }

    private val lengthRegex = Regex("^(-?[0-9]*\\.?[0-9]+)\\s*(em|rem|px|pt|%|ex|ch)?$")

    private fun lengthEm(v: String, percentToEm: Float): Float? {
        val m = lengthRegex.find(v.trim().lowercase()) ?: return null
        val num = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues[2]) {
            "em", "rem" -> num
            "px" -> num / 16f
            "pt" -> num / 12f
            "%" -> num * percentToEm
            "ex", "ch" -> num / 2f
            else -> if (num == 0f) 0f else null
        }
    }

    private fun fontScale(v: String, inherited: Float): Float? {
        val s = v.trim().lowercase()
        when (s) {
            "xx-small" -> return 0.6f
            "x-small" -> return 0.75f
            "small" -> return 0.89f
            "medium" -> return 1f
            "large" -> return 1.2f
            "x-large" -> return 1.5f
            "xx-large" -> return 2f
            "smaller" -> return inherited * 0.85f
            "larger" -> return inherited * 1.2f
        }
        val m = lengthRegex.find(s) ?: return null
        val num = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues[2]) {
            "em" -> inherited * num
            "%" -> inherited * num / 100f
            "rem" -> num
            "px" -> num / 16f
            "pt" -> num / 12f
            else -> null
        }
    }

    /** Maps each index of [src] onto [dst] (same text, possibly different whitespace). */
    private fun alignOffsets(src: String, dst: String): IntArray {
        val map = IntArray(src.length + 1)
        var i = 0
        var j = 0
        while (i < src.length) {
            when {
                j < dst.length && src[i] == dst[j] -> { map[i] = j; i++; j++ }
                src[i].isWhitespace() -> { map[i] = j; i++ }
                j < dst.length && dst[j].isWhitespace() -> j++
                else -> { map[i] = j.coerceAtMost(dst.length); i++; if (j < dst.length) j++ }
            }
        }
        map[src.length] = j.coerceAtMost(dst.length)
        return map
    }
}

/** Where a picture sits in a chapter: after [afterParagraph] readable paragraphs. */
data class ImageAnchor(val afterParagraph: Int, val zipPath: String, val alt: String?)

/**
 * Finds the chapter's pictures (`<img>` and SVG `<image>`) in document order and counts how many
 * readable paragraphs come before each one, using the same paragraph selection as the tokenizer.
 */
internal fun findImageAnchors(
    rawHtml: String,
    chapterPath: String,
    resolvePath: (baseDir: String, href: String) -> String
): List<ImageAnchor> {
    if (!looksLikeHtml(rawHtml) || !rawHtml.contains("<im", ignoreCase = true)) return emptyList()
    val doc = Jsoup.parse(rawHtml)
    doc.select("script, style, nav, head").remove()
    val body = doc.body() ?: return emptyList()
    val order = IdentityHashMap<Element, Int>()
    body.allElements.forEachIndexed { i, e -> order[e] = i }
    val blockOrder = readableBlocks(doc).map { order[it.first] ?: -1 }
    val dir = chapterPath.substringBeforeLast('/', "")
    // Jsoup's HTML parser turns SVG <image> into <img>; its link stays in xlink:href / href
    return body.select("img").mapNotNull { el ->
        val href = el.attr("src").ifBlank { el.attr("xlink:href") }.ifBlank { el.attr("href") }.trim()
        if (href.isEmpty() || href.startsWith("data:") || href.startsWith("http", ignoreCase = true)) return@mapNotNull null
        val position = order[el] ?: return@mapNotNull null
        ImageAnchor(
            afterParagraph = blockOrder.count { it in 0..position },
            zipPath = resolvePath(dir, href.substringBefore('#').substringBefore('?')),
            alt = el.attr("alt").takeIf { it.isNotBlank() }
        )
    }.distinctBy { it.afterParagraph to it.zipPath }
}

/** Fonts declared by `@font-face` rules across the given stylesheets, resolved to ZIP paths. */
internal fun collectFontFaces(
    stylesheets: Map<String, String>,
    resolvePath: (baseDir: String, href: String) -> String
): List<Pair<CssFontFace, String>> =
    stylesheets.flatMap { (path, css) ->
        val dir = path.substringBeforeLast('/', "")
        CssParser.parse(css).fontFaces.map { it to resolvePath(dir, it.src.substringBefore('#').substringBefore('?')) }
    }.distinctBy { it.second + "|" + it.first.family.lowercase() + "|" + it.first.weight + "|" + it.first.italic }

internal fun fontFaceToEmbedded(face: CssFontFace, filePath: String) =
    EmbeddedFontFace(family = face.family.lowercase(), path = filePath, weight = face.weight, italic = face.italic)
