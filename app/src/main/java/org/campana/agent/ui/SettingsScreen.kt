package org.campana.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.campana.agent.AppGraph

internal fun settingsTitle(page:String)=when(page){"account"->"Cuenta de ChatGPT";"permissions"->"Permisos y control";"apps"->"Aplicaciones";"help"->"Ayuda y diagnóstico";else->"Ajustes"}

@Composable internal fun SettingsScreen(g:AppGraph,page:String,onPage:(String)->Unit,requestNotices:()->Unit) {
    var revision by remember{mutableIntStateOf(0)}
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)revision++}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}
    }
    when(page) {
        "account"->AccountSettings(g,revision)
        "permissions"->PhonePermissions(g,revision,requestNotices)
        "apps"->SocialAppsSettings(g,revision,onPermissions={onPage("permissions")})
        "help"->HelpSettings(g,revision,onAccount={onPage("account")})
        else->{
            val configured=remember(revision){runCatching{g.auth.vault.active()?.accessToken?.isNotBlank()==true}.getOrDefault(false)}
            val uiEnabled=remember(revision){g.prefs.getBoolean("ui_enabled",false)}
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                ActionRow("Cuenta de ChatGPT",if(configured)"Cuenta configurada" else "Conecta tu cuenta para recibir respuestas",Icons.Outlined.PersonOutline){onPage("account")};HorizontalDivider()
                ActionRow("Permisos y control",if(uiEnabled)"Control de pantalla permitido" else "Control de pantalla desactivado",Icons.Outlined.Security){onPage("permissions")};HorizontalDivider()
                ActionRow("Aplicaciones","WhatsApp, Instagram y otras redes",Icons.Outlined.Apps){onPage("apps")};HorizontalDivider()
                ActionRow("Ayuda y diagnóstico","Pruebas locales y ayuda de la app",Icons.Outlined.HelpOutline){onPage("help")};HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Puedes conversar y preparar borradores sin activar el control de pantalla.",style=MaterialTheme.typography.bodyMedium)
                Text("Las campañas y los archivos se guardan en este teléfono.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
