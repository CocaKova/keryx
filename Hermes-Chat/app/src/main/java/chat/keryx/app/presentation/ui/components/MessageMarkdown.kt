package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

sealed class MessageBlock {
    data class TextBlock(val content: String) : MessageBlock()
    data class CodeBlock(val code: String, val language: String) : MessageBlock()
    data class ToolCallBlock(val toolName: String, val rawJson: String) : MessageBlock()
}

object MarkdownParser {
    fun parse(content: String): List<MessageBlock> {
        val blocks = mutableListOf<MessageBlock>()
        
        // Simple regex to find ```language ... ``` code blocks
        val codeBlockRegex = Regex("```(\\w*)\\n([\\s\\S]*?)```")
        
        var lastIndex = 0
        codeBlockRegex.findAll(content).forEach { matchResult ->
            // Add preceding text
            val textPart = content.substring(lastIndex, matchResult.range.first).trim()
            if (textPart.isNotEmpty()) {
                blocks.add(MessageBlock.TextBlock(textPart))
            }
            
            val language = matchResult.groupValues[1]
            val code = matchResult.groupValues[2].trim()
            
            // Check if it's a tool call (naive check for JSON with "action" or "tool")
            if ((language == "json" || language == "") && (code.contains("\"action\"") || code.contains("\"tool\""))) {
                try {
                    val json = Json { ignoreUnknownKeys = true }
                    val jsonElement = json.parseToJsonElement(code).jsonObject
                    val toolName = jsonElement["action"]?.toString() ?: jsonElement["tool"]?.toString() ?: "Unknown Action"
                    blocks.add(MessageBlock.ToolCallBlock(toolName.replace("\"", ""), code))
                } catch (e: Exception) {
                    blocks.add(MessageBlock.CodeBlock(code, language))
                }
            } else {
                blocks.add(MessageBlock.CodeBlock(code, language))
            }
            
            lastIndex = matchResult.range.last + 1
        }
        
        val remainingText = content.substring(lastIndex).trim()
        if (remainingText.isNotEmpty()) {
            blocks.add(MessageBlock.TextBlock(remainingText))
        }
        
        return if (blocks.isEmpty()) listOf(MessageBlock.TextBlock(content)) else blocks
    }
}

@Composable
fun ParsedMessageBlock(block: MessageBlock, isUser: Boolean, animationStyle: String) {
    when (block) {
        is MessageBlock.TextBlock -> {
            Text(
                text = parseInlineMarkdown(block.content),
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            )
        }
        is MessageBlock.CodeBlock -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    if (block.language.isNotEmpty()) {
                        Text(
                            text = block.language,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Text(
                        text = block.code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        is MessageBlock.ToolCallBlock -> {
            ToolCallAccordion(block.toolName, block.rawJson, animationStyle)
        }
    }
}

@Composable
fun ToolCallAccordion(toolName: String, rawJson: String, animationStyle: String) {
    var expanded by remember { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary
    
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, primaryColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                // If it's a tool call, show a mini version of our thinking animation to make it feel alive!
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = "Tool",
                    tint = primaryColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Executing: $toolName",
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand",
                    tint = primaryColor
                )
            }
            
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = rawJson,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun parseInlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        // Very naive bold / italic parser for prototype
        var currentText = text
        val regex = Regex("(\\*\\*(.*?)\\*\\*)|(\\_\\_(.*?)\\_\\_)")
        
        var lastMatch = 0
        regex.findAll(text).forEach { match ->
            append(text.substring(lastMatch, match.range.first))
            
            val boldText = match.groupValues[2]
            val italicText = match.groupValues[4]
            
            if (boldText.isNotEmpty()) {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(boldText)
                }
            } else if (italicText.isNotEmpty()) {
                withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(italicText)
                }
            }
            
            lastMatch = match.range.last + 1
        }
        append(text.substring(lastMatch))
    }
}
