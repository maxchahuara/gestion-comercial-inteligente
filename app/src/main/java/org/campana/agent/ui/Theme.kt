package org.campana.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Exact light tokens from the supplied Cobalto sereno specification. */
object Cobalt {
    val Primary=Color(0xFF1D4ED8);val Pressed=Color(0xFF1E40AF)
    val Accent=Color(0xFF14B8A6);val AccentAccessible=Color(0xFF0F766E)
    val Canvas=Color(0xFFF8FAFC);val Surface=Color(0xFFFFFFFF);val AiSoft=Color(0xFFEAF2FF)
    val Ink=Color(0xFF0F172A);val Secondary=Color(0xFF475569);val Muted=Color(0xFF64748B)
    val Border=Color(0xFFE2E8F0);val BorderStrong=Color(0xFFCBD5E1)
    val Subtle=Color(0xFFF1F5F9);val Selected=Color(0xFFEFF6FF)
    val Success=Color(0xFF15803D);val SuccessBg=Color(0xFFF0FDF4)
    val Warning=Color(0xFFB45309);val WarningBg=Color(0xFFFFFBEB)
    val Error=Color(0xFFB91C1C);val ErrorBg=Color(0xFFFEF2F2)
}
object CobaltSpace {
    val Xs=4.dp;val Sm=8.dp;val Md=12.dp;val Lg=16.dp;val Page=20.dp
    val Xl=24.dp;val Section=32.dp;val Touch=48.dp
}
object CobaltStroke { val Default=1.dp;val Focused=2.dp }
@Immutable data class CampaignVisualColors(
    val pressed:Color,val selected:Color,val assistant:Color,
    val success:Color,val successBg:Color,val warning:Color,val warningBg:Color,
    val info:Color,val infoBg:Color,val neutral:Color,val neutralBg:Color
)
private val LightVisual=CampaignVisualColors(Cobalt.Pressed,Cobalt.Selected,Cobalt.AiSoft,
    Cobalt.Success,Cobalt.SuccessBg,Cobalt.Warning,Cobalt.WarningBg,Cobalt.Primary,Cobalt.AiSoft,Cobalt.Secondary,Cobalt.Subtle)
private val DarkVisual=CampaignVisualColors(Color(0xFF91B1F5),Color(0xFF1B3156),Color(0xFF192C4C),
    Color(0xFF86DCA3),Color(0xFF153323),Color(0xFFF6C271),Color(0xFF3B2B15),Color(0xFFADC6FF),Color(0xFF192C4C),Color(0xFFCBD5E1),Color(0xFF233047))
val LocalCampaignVisualColors=staticCompositionLocalOf{LightVisual}
private val Light=lightColorScheme(
    primary=Cobalt.Primary,onPrimary=Cobalt.Surface,primaryContainer=Cobalt.AiSoft,onPrimaryContainer=Cobalt.Ink,
    secondary=Cobalt.Secondary,onSecondary=Cobalt.Surface,secondaryContainer=Cobalt.Subtle,onSecondaryContainer=Cobalt.Ink,
    tertiary=Cobalt.AccentAccessible,onTertiary=Cobalt.Surface,tertiaryContainer=Color(0xFFDAF5EF),onTertiaryContainer=Cobalt.Ink,
    background=Cobalt.Canvas,onBackground=Cobalt.Ink,surface=Cobalt.Surface,onSurface=Cobalt.Ink,
    surfaceDim=Cobalt.Subtle,surfaceBright=Cobalt.Surface,surfaceVariant=Cobalt.Subtle,onSurfaceVariant=Cobalt.Secondary,
    surfaceContainerLowest=Cobalt.Surface,surfaceContainerLow=Cobalt.Canvas,surfaceContainer=Cobalt.Surface,
    surfaceContainerHigh=Cobalt.Subtle,surfaceContainerHighest=Cobalt.Border,surfaceTint=Cobalt.Primary,
    outline=Cobalt.Muted,outlineVariant=Cobalt.Border,
    error=Cobalt.Error,onError=Cobalt.Surface,errorContainer=Cobalt.ErrorBg,onErrorContainer=Cobalt.Error,
    inverseSurface=Cobalt.Ink,inverseOnSurface=Cobalt.Canvas,inversePrimary=Color(0xFFADC6FF),scrim=Color.Black)
// Preserve existing system-dark behavior with accessible cobalt/slate counterparts.
private val Dark=darkColorScheme(
    primary=Color(0xFFADC6FF),onPrimary=Color(0xFF10254E),primaryContainer=Color(0xFF1B3156),onPrimaryContainer=Color(0xFFE3ECFF),
    secondary=Color(0xFFCBD5E1),onSecondary=Cobalt.Ink,secondaryContainer=Color(0xFF233047),onSecondaryContainer=Cobalt.Canvas,
    tertiary=Color(0xFF77DCCE),onTertiary=Color(0xFF063A35),tertiaryContainer=Color(0xFF123C38),onTertiaryContainer=Color(0xFFDAF5EF),
    background=Cobalt.Ink,onBackground=Cobalt.Canvas,surface=Color(0xFF17243A),onSurface=Cobalt.Canvas,
    surfaceDim=Cobalt.Ink,surfaceBright=Color(0xFF233047),surfaceVariant=Color(0xFF233047),onSurfaceVariant=Cobalt.BorderStrong,
    surfaceContainerLowest=Cobalt.Ink,surfaceContainerLow=Color(0xFF142036),surfaceContainer=Color(0xFF17243A),
    surfaceContainerHigh=Color(0xFF233047),surfaceContainerHighest=Color(0xFF2C3C54),surfaceTint=Color(0xFFADC6FF),
    outline=Color(0xFF94A3B8),outlineVariant=Cobalt.Secondary,
    error=Color(0xFFFFB4AB),onError=Color(0xFF560D12),errorContainer=Color(0xFF411A21),onErrorContainer=Color(0xFFFFC8C2),
    inverseSurface=Cobalt.Border,inverseOnSurface=Cobalt.Ink,inversePrimary=Cobalt.Primary,scrim=Color.Black)
private fun type(size:Int,line:Int,weight:FontWeight)=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=size.sp,lineHeight=line.sp,fontWeight=weight)
private val CobaltType=Typography(
    displayLarge=type(28,34,FontWeight.Bold),displayMedium=type(28,34,FontWeight.Bold),displaySmall=type(28,34,FontWeight.Bold),
    headlineLarge=type(28,34,FontWeight.Bold),headlineMedium=type(24,30,FontWeight.SemiBold),headlineSmall=type(24,30,FontWeight.SemiBold),
    titleLarge=type(24,30,FontWeight.SemiBold),titleMedium=type(20,26,FontWeight.SemiBold),titleSmall=type(16,24,FontWeight.SemiBold),
    bodyLarge=type(16,24,FontWeight.Normal),bodyMedium=type(16,24,FontWeight.Normal),bodySmall=type(14,20,FontWeight.Normal),
    labelLarge=type(14,20,FontWeight.Medium),labelMedium=type(14,20,FontWeight.Medium),labelSmall=type(12,16,FontWeight.Medium))
private val CobaltShapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(8.dp),
    medium=RoundedCornerShape(12.dp),large=RoundedCornerShape(16.dp),extraLarge=RoundedCornerShape(24.dp))
@Composable fun CampaignTheme(darkTheme:Boolean=isSystemInDarkTheme(),content:@Composable ()->Unit) {
    val scheme=if(darkTheme)Dark else Light
    CompositionLocalProvider(LocalCampaignVisualColors provides if(darkTheme)DarkVisual else LightVisual,
        LocalTextSelectionColors provides TextSelectionColors(scheme.primary,scheme.primaryContainer)) {
        MaterialTheme(colorScheme=scheme,typography=CobaltType,shapes=CobaltShapes,content=content)
    }
}
