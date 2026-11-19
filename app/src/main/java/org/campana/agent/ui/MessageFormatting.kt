package org.campana.agent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

internal data class MessageBlock(val kind:String,val text:String)

// Display-only formatting: no HTML, embedded media, executable links or network loading.
internal fun messageBlocks(source:String):List<MessageBlock> {
    val lines=source.lines();val blocks=mutableListOf<MessageBlock>();var i=0
    while(i<lines.size) {
        val line=lines[i];val trimmed=line.trim()
        if(trimmed.startsWith("```")) {
            val code=mutableListOf<String>();i++
            while(i<lines.size && !lines[i].trim().startsWith("```")){code+=lines[i];i++}
            blocks+=MessageBlock("code",code.joinToString("\n"));i++;continue
        }
        if(trimmed.startsWith("|") && i+1<lines.size && Regex("^[| :\\-]+$").matches(lines[i+1].trim())) {
            val headers=trimmed.trim('|').split('|').map { it.trim() };i+=2
            while(i<lines.size && lines[i].trim().startsWith("|")) {
                val cells=lines[i].trim().trim('|').split('|').map { it.trim() }
                blocks+=MessageBlock("table",cells.mapIndexed { index,cell->"**"+(headers.getOrNull(index) ?: "Dato")+":** "+cell }.joinToString("\n"))
                i++
            }
            continue
        }
        if(trimmed.isNotBlank()) {
            val heading=Regex("^#{1,6}\\s+").find(trimmed)
            val bullet=Regex("^[-*+]\\s+").find(trimmed)
            blocks+=when {
                heading!=null->MessageBlock("heading",trimmed.removeRange(heading.range))
                bullet!=null->MessageBlock("list","• "+trimmed.removeRange(bullet.range))
                trimmed in setOf("---","***","___")->MessageBlock("space","")
                else->MessageBlock("text",line)
            }
        }
        i++
    }
    return blocks
}

internal fun inlineMessage(source:String):AnnotatedString = buildAnnotatedString {
    val pattern=Regex("\\*\\*([^*]+)\\*\\*|`([^`]+)`|\\[([^]]+)]\\((https?://[^)]+)\\)")
    var end=0
    pattern.findAll(source).forEach { match->
        append(source.substring(end,match.range.first))
        when {
            match.groups[1]!=null->withStyle(SpanStyle(fontWeight=FontWeight.SemiBold)){append(match.groupValues[1])}
            match.groups[2]!=null->withStyle(SpanStyle(fontFamily=FontFamily.Monospace)){append(match.groupValues[2])}
            else->append(match.groupValues[3]+" ("+match.groupValues[4]+")")
        }
        end=match.range.last+1
    }
    append(source.substring(end))
}

internal fun plainMessage(source:String):String=messageBlocks(source).joinToString("\n\n") { if(it.kind=="code")it.text else inlineMessage(it.text).text }

@Composable internal fun ReadableMessage(source:String) {
    val blocks=remember(source){messageBlocks(source)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block->
            when(block.kind) {
                "space"->Spacer(Modifier.height(4.dp))
                "code"->Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small) {
                    Text(block.text,Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodyMedium)
                }
                "table"->Column(Modifier.padding(vertical=6.dp)) { Text(inlineMessage(block.text),style=MaterialTheme.typography.bodyMedium) }
                else->Text(inlineMessage(block.text),style=if(block.kind=="heading")MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight=if(block.kind=="heading")FontWeight.SemiBold else null)
            }
        }
    }
}
