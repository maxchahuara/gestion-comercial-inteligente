package org.campana.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CampTopAppBar(title:@Composable ()->Unit,navigationIcon:@Composable ()->Unit={},
    actions:@Composable RowScope.()->Unit={},hasSubtitle:Boolean=false) {
    val height=with(LocalDensity.current) {
        MaterialTheme.typography.titleLarge.lineHeight.toDp()+
            (if(hasSubtitle)MaterialTheme.typography.labelSmall.lineHeight.toDp() else 0.dp)+16.dp
    }.coerceAtLeast(64.dp)
    TopAppBar(title=title,navigationIcon=navigationIcon,actions=actions,expandedHeight=height)
}

/** Styling delegates to native Material controls; callbacks and semantics are unchanged. */
@Composable internal fun CampButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    colors:ButtonColors?=null,content:@Composable RowScope.()->Unit) {
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState()
    Button(onClick,modifier.heightIn(min=CobaltSpace.Touch),enabled,shape=MaterialTheme.shapes.large,
        colors=colors ?: ButtonDefaults.buttonColors(containerColor=if(pressed)LocalCampaignVisualColors.current.pressed else MaterialTheme.colorScheme.primary),
        interactionSource=interaction,content=content)
}
@Composable internal fun CampOutlinedButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable RowScope.()->Unit) {
    OutlinedButton(onClick,modifier.heightIn(min=CobaltSpace.Touch),enabled,shape=MaterialTheme.shapes.large,
        colors=ButtonDefaults.outlinedButtonColors(containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.primary),
        border=BorderStroke(CobaltStroke.Default,if(enabled)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),content=content)
}
@Composable internal fun CampTextButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    colors:ButtonColors=ButtonDefaults.textButtonColors(),content:@Composable RowScope.()->Unit) {
    TextButton(onClick,modifier.heightIn(min=CobaltSpace.Touch),enabled,shape=MaterialTheme.shapes.medium,colors=colors,content=content)
}
@Composable internal fun CampOutlinedCard(modifier:Modifier=Modifier,
    colors:CardColors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surface),
    border:BorderStroke=BorderStroke(CobaltStroke.Default,MaterialTheme.colorScheme.outlineVariant),content:@Composable ColumnScope.()->Unit) {
    OutlinedCard(modifier,shape=MaterialTheme.shapes.large,colors=colors,border=border,content=content)
}
@Composable internal fun CampOutlinedCard(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    colors:CardColors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surface),
    border:BorderStroke=BorderStroke(CobaltStroke.Default,MaterialTheme.colorScheme.outlineVariant),content:@Composable ColumnScope.()->Unit) {
    OutlinedCard(onClick,modifier,enabled,shape=MaterialTheme.shapes.large,colors=colors,border=border,content=content)
}
@Composable internal fun CampTextField(value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,
    enabled:Boolean=true,readOnly:Boolean=false,textStyle:TextStyle=LocalTextStyle.current,
    label:(@Composable ()->Unit)?=null,placeholder:(@Composable ()->Unit)?=null,
    leadingIcon:(@Composable ()->Unit)?=null,trailingIcon:(@Composable ()->Unit)?=null,
    supportingText:(@Composable ()->Unit)?=null,isError:Boolean=false,
    visualTransformation:VisualTransformation=VisualTransformation.None,keyboardOptions:KeyboardOptions=KeyboardOptions.Default,
    keyboardActions:KeyboardActions=KeyboardActions.Default,singleLine:Boolean=false,maxLines:Int=if(singleLine)1 else Int.MAX_VALUE,minLines:Int=1) {
    val c=MaterialTheme.colorScheme
    OutlinedTextField(value,onValueChange,modifier,enabled,readOnly,textStyle,label,placeholder,leadingIcon,trailingIcon,
        supportingText=supportingText,isError=isError,visualTransformation=visualTransformation,keyboardOptions=keyboardOptions,
        keyboardActions=keyboardActions,singleLine=singleLine,maxLines=maxLines,minLines=minLines,shape=MaterialTheme.shapes.medium,
        colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=c.surface,unfocusedContainerColor=c.surface,disabledContainerColor=c.surfaceVariant,
            errorContainerColor=c.surface,focusedBorderColor=c.primary,unfocusedBorderColor=c.outline,focusedLabelColor=c.primary,
            unfocusedLabelColor=c.onSurfaceVariant,focusedPlaceholderColor=c.onSurfaceVariant,unfocusedPlaceholderColor=c.onSurfaceVariant))
}
