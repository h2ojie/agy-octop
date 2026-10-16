package com.octop.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

private val markdownParser: Parser by lazy {
    Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .build()
}

@Composable
fun MarkdownView(
    content: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val document = remember(content) {
        markdownParser.parse(content)
    }

    SelectionContainer {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            var child = document.firstChild
            while (child != null) {
                MarkdownNode(node = child)
                child = child.next
            }

            if (isStreaming) {
                DisableSelection {
                    Text(
                        text = " ▍",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun MarkdownNode(node: Node) {
    val isDark = isSystemInDarkTheme()

    when (node) {
        is Heading -> {
            val inlineText = remember(node) { buildInlineAnnotatedString(node, isDark) }
            val (fontSize, fontWeight, color) = when (node.level) {
                1 -> Triple(20.sp, FontWeight.Bold, MaterialTheme.colorScheme.primary)
                2 -> Triple(18.sp, FontWeight.Bold, MaterialTheme.colorScheme.onSurface)
                3 -> Triple(16.sp, FontWeight.Bold, MaterialTheme.colorScheme.onSurface)
                else -> Triple(15.sp, FontWeight.SemiBold, MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = inlineText,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = color,
                lineHeight = (fontSize.value * 1.4f).sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
            )
        }

        is Paragraph -> {
            val inlineText = remember(node) { buildInlineAnnotatedString(node, isDark) }
            Text(
                text = inlineText,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        is FencedCodeBlock -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            val lang = node.info?.ifBlank { "code" } ?: "code"
            val code = node.literal?.trimEnd() ?: ""
            CodeBlock(
                language = lang,
                code = code,
                onCopy = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(lang, code))
                    android.widget.Toast.makeText(context, "代码已复制", android.widget.Toast.LENGTH_SHORT).show()
                }
            )
        }

        is IndentedCodeBlock -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            val code = node.literal?.trimEnd() ?: ""
            CodeBlock(
                language = "code",
                code = code,
                onCopy = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                    android.widget.Toast.makeText(context, "代码已复制", android.widget.Toast.LENGTH_SHORT).show()
                }
            )
        }

        is BlockQuote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    var inner = node.firstChild
                    while (inner != null) {
                        MarkdownNode(node = inner)
                        inner = inner.next
                    }
                }
            }
        }

        is BulletList -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "•",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                var inner = item.firstChild
                                while (inner != null) {
                                    MarkdownNode(node = inner)
                                    inner = inner.next
                                }
                            }
                        }
                    }
                    item = item.next
                }
            }
        }

        is OrderedList -> {
            var index = node.startNumber
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "$index.",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                var inner = item.firstChild
                                while (inner != null) {
                                    MarkdownNode(node = inner)
                                    inner = inner.next
                                }
                            }
                        }
                        index++
                    }
                    item = item.next
                }
            }
        }

        is ThematicBreak -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        is TableBlock -> {
            val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            val headerBg = if (isDark) Color(0xFF262C36) else Color(0xFFF1F3F5)
            val zebraBg = if (isDark) Color(0xFF1B1F27).copy(alpha = 0.5f) else Color(0xFFF9FAFB)

            val rows = remember(node, isDark) {
                val list = mutableListOf<Pair<Boolean, List<AnnotatedString>>>()
                var section = node.firstChild
                while (section != null) {
                    val isHeader = section is TableHead
                    var row = section.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            val cells = mutableListOf<AnnotatedString>()
                            var cell = row.firstChild
                            while (cell != null) {
                                if (cell is TableCell) {
                                    cells.add(buildInlineAnnotatedString(cell, isDark))
                                }
                                cell = cell.next
                            }
                            if (cells.isNotEmpty()) {
                                list.add(Pair(isHeader, cells))
                            }
                        }
                        row = row.next
                    }
                    section = section.next
                }
                list
            }

            if (rows.isNotEmpty()) {
                val numCols = remember(rows) { rows.maxOf { it.second.size } }

                val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                // 计算每一列的最佳统一宽度（该列所有单元格宽度严格相等，保证垂直网格线100%对齐，且随系统字号缩放等比缩放）
                val columnWidths = remember(rows, numCols, fontScale) {
                    val widths = MutableList(numCols) { 0f }
                    val cjkCharWidth = 14f * fontScale
                    val asciiCharWidth = 7.5f * fontScale
                    for ((_, cells) in rows) {
                        for (colIdx in 0 until numCols) {
                            val cellText = if (colIdx < cells.size) cells[colIdx].text else ""
                            val lines = cellText.split('\n')
                            var maxLineWidth = 0f
                            for (line in lines) {
                                var lineWidth = 0f
                                for (ch in line) {
                                    lineWidth += if (ch.code > 127) cjkCharWidth else asciiCharWidth
                                }
                                if (lineWidth > maxLineWidth) maxLineWidth = lineWidth
                            }
                            if (maxLineWidth > widths[colIdx]) {
                                widths[colIdx] = maxLineWidth
                            }
                        }
                    }
                    widths.map { contentWidth ->
                        // 左右 padding (24dp) + 缓冲边距 (12dp) 随字号动态伸缩
                        val needed = (contentWidth + 36f * fontScale).dp
                        val minWidth = (88f * fontScale).dp
                        val maxWidth = (260f * fontScale).dp
                        needed.coerceIn(minWidth, maxWidth)
                    }
                }

                val scrollState = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .horizontalScroll(scrollState)
                        .clip(RoundedCornerShape(8.dp))
                        .border(0.75.dp, borderColor, RoundedCornerShape(8.dp))
                ) {
                    rows.forEachIndexed { rowIndex, (isHeader, cells) ->
                        val rowBg = when {
                            isHeader -> headerBg
                            rowIndex % 2 == 1 -> zebraBg
                            else -> Color.Transparent
                        }

                        Row(
                            modifier = Modifier
                                .height(IntrinsicSize.Min)
                                .background(rowBg)
                        ) {
                            for (colIdx in 0 until numCols) {
                                val cellContent = if (colIdx < cells.size) cells[colIdx] else AnnotatedString("")
                                val colWidth = columnWidths.getOrElse(colIdx) { 88.dp }

                                Box(
                                    modifier = Modifier
                                        .width(colWidth)
                                        .fillMaxHeight()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = cellContent,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                // 列之间绘制垂直分割线，贯通整行高度
                                if (colIdx < numCols - 1) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(0.75.dp)
                                            .background(borderColor)
                                    )
                                }
                            }
                        }

                        // 行之间绘制水平分割线
                        if (rowIndex < rows.size - 1) {
                            HorizontalDivider(
                                thickness = if (isHeader) 1.dp else 0.5.dp,
                                color = borderColor
                            )
                        }
                    }
                }
            }
        }

        is Text -> {
            Text(
                text = node.literal ?: "",
                fontSize = 15.sp,
                lineHeight = 23.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        is CustomBlock, is CustomNode -> {
            var child = node.firstChild
            while (child != null) {
                MarkdownNode(node = child)
                child = child.next
            }
        }

        else -> {
            // 通用回退
            var child = node.firstChild
            while (child != null) {
                MarkdownNode(node = child)
                child = child.next
            }
        }
    }
}

fun buildInlineAnnotatedString(node: Node, isDark: Boolean): AnnotatedString {
    return buildAnnotatedString {
        appendInlineChildren(node, this, isDark)
    }
}

private fun appendInlineChildren(parent: Node, builder: AnnotatedString.Builder, isDark: Boolean) {
    var child = parent.firstChild
    while (child != null) {
        when (child) {
            is Text -> {
                builder.append(child.literal ?: "")
            }
            is StrongEmphasis -> {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInlineChildren(child, builder, isDark)
                builder.pop()
            }
            is Emphasis -> {
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInlineChildren(child, builder, isDark)
                builder.pop()
            }
            is Code -> {
                val inlineCodeBg = if (isDark) Color(0xFF2D3748) else Color(0xFFE2E8F0)
                val inlineCodeColor = if (isDark) Color(0xFFF6AD55) else Color(0xFFC05621)
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.5.sp,
                        background = inlineCodeBg,
                        color = inlineCodeColor
                    )
                )
                builder.append(" ${child.literal ?: ""} ")
                builder.pop()
            }
            is Link -> {
                builder.pushStyle(
                    SpanStyle(
                        color = Color(0xFF3B82F6),
                        textDecoration = TextDecoration.Underline
                    )
                )
                appendInlineChildren(child, builder, isDark)
                builder.pop()
            }
            is SoftLineBreak -> {
                builder.append(" ")
            }
            is HardLineBreak -> {
                builder.append("\n")
            }
            else -> {
                appendInlineChildren(child, builder, isDark)
            }
        }
        child = child.next
    }
}
