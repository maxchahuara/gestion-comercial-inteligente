package org.campana.agent.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.campana.agent.AppGraph
import org.campana.agent.data.Channel
import org.campana.agent.net.safeError
import org.campana.agent.phone.*

@Composable internal fun PhonePermissions(g:AppGraph,revision:Int,requestNotices:()->Unit) {
    var uiEnabled by remember(revision){mutableStateOf(g.prefs.getBoolean("ui_enabled",false))}
    var help by remember{mutableStateOf(false)}
    val accessibility=remember(revision){Settings.Secure.getString(g.context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(":")?.any { ComponentName.unflattenFromString(it)==ComponentName(g.context,PhoneAccessibilityService::class.java) }==true}
    val notifications=remember(revision){NotificationManagerCompat.getEnabledListenerPackages(g.context).contains(g.context.packageName)}
    val accessibilityConnected by PhoneAccessibilityService.connected.collectAsStateWithLifecycle()
    val notificationConnected by SocialNotificationService.connected.collectAsStateWithLifecycle()
    val appNotices=remember(revision){NotificationManagerCompat.from(g.context).areNotificationsEnabled()}
    val channelNotices=remember(revision){g.context.getSystemService(NotificationManager::class.java).getNotificationChannel("agent")?.let{it.importance!=NotificationManager.IMPORTANCE_NONE}==true}
    val appName = g.context.getString(org.campana.agent.R.string.app_name)
    fun open(intent:Intent){runCatching{g.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure{g.status.value="No se pudo abrir el ajuste. Busca $appName en los ajustes de Android."}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        SwitchRow("Permitir control de pantalla","Puede abrir y operar las redes autorizadas. No activa el modo automático de las campañas.",uiEnabled,{value->
            uiEnabled=value;g.prefs.edit().putBoolean("ui_enabled",value).apply();if(!value && g.visualWorking.value)g.pause(true)
        })
        Text("Durante el control de pantalla verás un halo azul y Recuperar control. Ese botón pausa el agente y te devuelve el teléfono; reanuda desde la app cuando quieras.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        PermissionItem("Accesibilidad","Lee pantallas y permite pulsaciones dentro de las redes autorizadas.",if(!accessibility)"Sin activar" else if(accessibilityConnected)"Permiso concedido · servicio conectado" else "Permiso concedido · servicio sin conectar"){
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if(accessibility && !accessibilityConnected)Text("Si acabas de actualizar o detener la app, desactiva y vuelve a activar el servicio de la app en Accesibilidad.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        PermissionItem("Acceso a notificaciones","Detecta consultas y permite respuestas cuando la notificación lo admite.",if(!notifications)"Sin activar" else if(notificationConnected)"Permiso concedido · servicio conectado" else "Permiso concedido · servicio sin conectar"){
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        HorizontalDivider()
        PermissionItem("Avisos del agente","Muestra el trabajo en curso y la acción Pausar.",if(appNotices && channelNotices)"Activados" else "Desactivados"){
            if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(g.context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestNotices()
            else if(!appNotices)open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,g.context.packageName))
            else open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,g.context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,"agent"))
        }
        HorizontalDivider()
        Text("Los permisos no sustituyen las reglas de cada campaña. Puedes preparar borradores sin permitir el control de pantalla.",style=MaterialTheme.typography.bodyMedium)
        CampTextButton(onClick={help=true},modifier=Modifier.heightIn(min=48.dp)){Text("No puedo activar un permiso")}
        CampTextButton(onClick={open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+g.context.packageName)))},modifier=Modifier.heightIn(min=48.dp)){Text("Abrir ajustes de la app en Android")}
    }
    if(help)AlertDialog(onDismissRequest={help=false},title={Text("Activar permisos desde Android")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("En Accesibilidad, busca los servicios instalados y elige $appName. Para notificaciones, activa el acceso de la app.")
        if(Build.VERSION.SDK_INT>=33)Text("Si Android bloquea un APK instalado fuera de la tienda, abre los ajustes de la app y revisa el menú de ajustes restringidos. Después vuelve a activar el permiso.")
        Text("Si el servicio aparece sin conectar después de una actualización, desactívalo y vuelve a activarlo.")
    }},confirmButton={CampTextButton(onClick={help=false}){Text("Entendido")}})
}

@Composable private fun PermissionItem(title:String,description:String,state:String,onOpen:()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        SectionHeading(title,description)
        Text(state,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        CampOutlinedButton(onClick=onOpen,modifier=Modifier.heightIn(min=48.dp)){Text("Revisar "+title.lowercase())}
    }
}

@Composable internal fun SocialAppsSettings(g:AppGraph,revision:Int,onPermissions:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Usa las aplicaciones instaladas con tu sesión abierta. Abrirlas aquí pausa el agente para que puedas revisarlas.",style=MaterialTheme.typography.bodyMedium)
        Channel.entries.forEach { channel->
            val installed=remember(revision,channel){SocialApps.installed(g.context,channel)}
            val entry=remember(revision,channel){if(installed)SocialApps.launchIntent(g.context,channel) else null}
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SectionHeading(channel.title,if(installed)"Instalada · funciones en pruebas" else "No instalada o deshabilitada")
                if(entry!=null)CampOutlinedButton(onClick={
                    runCatching{g.pause(true);g.context.startActivity(Intent(entry).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.onFailure{g.status.value=safeError(it)}
                },modifier=Modifier.heightIn(min=48.dp)){Text("Abrir "+channel.title)}
                else Text(if(installed)"Android no ofrece una entrada para abrirla aquí. Revísala desde el inicio del teléfono." else "Instálala o habilítala en Android para utilizarla.",style=MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
        Text("Estar instalada no garantiza compatibilidad ni una sesión abierta. Cada función depende de la versión y la cuenta.",style=MaterialTheme.typography.bodyMedium)
        Text("WhatsApp usa tu registro normal del teléfono; no requiere vinculación ni WhatsApp Business.",style=MaterialTheme.typography.bodyMedium)
        CampTextButton(onClick=onPermissions,modifier=Modifier.heightIn(min=48.dp)){Text("Revisar permisos y control")}
    }
}
