package org.campana.agent.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.selection.SelectionContainer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.campana.agent.AppGraph
import org.campana.agent.net.safeError

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AccountSettings(g:AppGraph,lifecycleRevision:Int) {
    var revision by remember{mutableIntStateOf(0)}
    var connecting by remember{mutableStateOf(false)};var loading by remember{mutableStateOf(false)}
    var modelsOpen by remember{mutableStateOf(false)};var accountsOpen by remember{mutableStateOf(false)}
    var disconnect by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    val session=remember(revision,lifecycleRevision){runCatching{g.auth.vault.active()}.getOrNull()}
    val sessions=remember(revision,lifecycleRevision){runCatching{g.auth.vault.read().sessions}.getOrDefault(emptyList())}
    val configured=session?.accessToken?.isNotBlank()==true
    val models by g.models.collectAsStateWithLifecycle();val model by g.model.collectAsStateWithLifecycle()
    val title=models.firstOrNull{it.first==model}?.second ?: g.prefs.getString("model_title_"+model,model).orEmpty()
    fun refresh() {
        if(loading)return
        loading=true;error=""
        g.scope.launch {
            runCatching{g.refreshModels()}.onFailure{error=safeError(it)}
            loading=false
        }
    }
    fun login(addAccount:Boolean) {
        if(connecting)return
        connecting=true;error="";g.pause(true)
        g.scope.launch {
            runCatching{g.auth.login(if(addAccount)null else session?.clientId);g.refreshModels()}
                .onSuccess{g.status.value="Cuenta configurada. Revisa el modelo y reanuda cuando estés listo."}
                .onFailure{error=safeError(it)}
            connecting=false;revision++
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        SectionHeading(if(configured)"Cuenta configurada" else "Conecta tu cuenta",if(configured)session?.email.orEmpty() else "Autoriza ChatGPT desde el navegador de este teléfono. Tus campañas se conservan aquí.")
        if(connecting) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Termina la autorización en el navegador y vuelve a la app. El agente queda pausado.",style=MaterialTheme.typography.bodyMedium)
        }
        if(error.isNotBlank()) {
            Text(error,color=MaterialTheme.colorScheme.error)
            if(configured)CampTextButton(onClick={login(false)},enabled=!connecting){Text("Reconectar esta cuenta")}
        }
        if(!configured) {
            CampButton(onClick={login(false)},enabled=!connecting,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(session!=null)"Reconectar esta cuenta" else "Continue with ChatGPT")}
            if(session!=null)CampTextButton(onClick={login(true)},enabled=!connecting){Text("Usar otra cuenta de ChatGPT")}
        } else {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SectionHeading("Modelo")
                CampOutlinedButton(onClick={modelsOpen=true;if(models.isEmpty())refresh()},enabled=!connecting,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){
                    Text(title.ifBlank{"Elegir modelo"},Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,"Elegir modelo")
                }
                Text("Los modelos y límites dependen de tu cuenta.",style=MaterialTheme.typography.bodySmall)
            }
            CampOutlinedButton(onClick=::refresh,enabled=!loading && !connecting,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(loading)"Actualizando modelos…" else "Actualizar modelos")}
            if(sessions.size>1)CampOutlinedButton(onClick={accountsOpen=true},enabled=!connecting,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Cambiar cuenta guardada")}
            CampTextButton(onClick={login(true)},enabled=!connecting,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Añadir otra cuenta")}
            HorizontalDivider()
            Text("Al cambiar o reconectar una cuenta, el agente se pausa para evitar mezclar sesiones.",style=MaterialTheme.typography.bodySmall)
            CampTextButton(onClick={disconnect=true},enabled=!connecting,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),modifier=Modifier.heightIn(min=48.dp)){Text("Desconectar cuenta")}
            Text("Tus campañas y archivos seguirán en el teléfono.",style=MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        ImageGenerationSettings(g)
    }
    if(modelsOpen)ModalBottomSheet(onDismissRequest={modelsOpen=false}) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SectionHeading("Elegir modelo","Opciones disponibles para la cuenta seleccionada.")
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
            if(models.isEmpty() && !loading)Text("No se cargó la lista. Comprueba tu conexión y actualiza los modelos.")
            LazyColumn(Modifier.heightIn(max=420.dp)) {
                items(models,key={it.first}){(id,label)->
                    ListItem(headlineContent={Text(label)},trailingContent={if(id==model)Icon(Icons.Outlined.Check,"Seleccionado")},modifier=Modifier.clickable{g.selectModel(id);modelsOpen=false}.heightIn(min=56.dp))
                }
            }
            CampTextButton(onClick=::refresh,enabled=!loading){Text("Actualizar lista")}
        }
    }
    if(accountsOpen)ModalBottomSheet(onDismissRequest={accountsOpen=false}) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SectionHeading("Cambiar cuenta","Al cambiar de cuenta se pausará el agente. Puedes reanudarlo tras revisar el modelo.")
            LazyColumn(Modifier.heightIn(max=420.dp)) {
                items(sessions,key={it.clientId}){account->
                    ListItem(headlineContent={Text(account.email.ifBlank{"Cuenta guardada"})},supportingContent={Text(if(account.accessToken.isBlank())"Sesión cerrada" else if(account.clientId==session?.clientId)"Seleccionada" else "Disponible en este teléfono")},modifier=Modifier.clickable{
                        g.pause(true);g.auth.vault.select(account.clientId);g.selectModel("");g.models.value=emptyList();accountsOpen=false;revision++
                        if(account.accessToken.isNotBlank())refresh()
                    }.heightIn(min=64.dp))
                }
            }
            CampTextButton(onClick={accountsOpen=false;login(true)}){Text("Añadir otra cuenta")}
        }
    }
    if(disconnect)AlertDialog(onDismissRequest={disconnect=false},title={Text("¿Desconectar esta cuenta?")},text={Text("Se pausará el agente y esta cuenta dejará de usarse en esta app. Tus campañas y archivos no se borrarán.")},
        confirmButton={CampTextButton(onClick={disconnect=false;connecting=true;g.pause(true);g.scope.launch{
            runCatching{g.auth.signOut()}.onSuccess{revoked->g.selectModel("");g.models.value=emptyList();g.status.value=if(revoked)"Cuenta desconectada. El agente está pausado." else "Sesión cerrada en este teléfono. No se confirmó la revocación remota; revísala desde ChatGPT."}.onFailure{error=safeError(it)}
            connecting=false;revision++
        }}){Text("Desconectar y pausar")}},dismissButton={CampTextButton(onClick={disconnect=false}){Text("Conservar cuenta")}})
}

@Composable private fun ImageGenerationSettings(g: AppGraph) {
    val account by g.codexImages.state.collectAsStateWithLifecycle()
    var acting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun openVerification(url: String) {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" && uri.host in setOf("auth.openai.com", "chatgpt.com", "auth0.openai.com") && uri.userInfo == null)
        g.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun login() {
        acting = true
        error = ""
        scope.launch {
            runCatching { g.codexImages.login(device = true) }.onFailure { error = safeError(it) }
            acting = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Codex para imágenes", "Genera piezas con tu cuenta de ChatGPT desde el motor Codex de este teléfono.")
        Text(if (account.signedIn) "Codex conectado." else "Conecta Codex una vez para usar su generador de imágenes.")
        if (!account.signedIn && account.deviceCode.isBlank()) {
            CampOutlinedButton(onClick = ::login, enabled = !acting) { Text("Conectar Codex para imágenes") }
        }
        if (account.deviceCode.isNotBlank()) {
            Text("Introduce este código en la página de verificación y vuelve a la app.")
            SelectionContainer { Text(account.deviceCode, style = MaterialTheme.typography.titleLarge) }
            CampTextButton(onClick = { runCatching { openVerification(account.loginUrl) }.onFailure { error = safeError(it) } }) {
                Text("Abrir verificación")
            }
            CampTextButton(onClick = { scope.launch { g.codexImages.cancelLogin() } }) { Text("Cancelar conexión") }
        }
        if (account.signedIn) CampTextButton(onClick = {
            scope.launch { runCatching { g.codexImages.logout(); g.codexImages.stop() }.onFailure { error = safeError(it) } }
        }) { Text("Desconectar Codex para imágenes") }
        CampTextButton(onClick = {
            acting = true
            error = ""
            scope.launch {
                runCatching { g.codexImages.refreshAccount() }.onFailure { error = safeError(it) }
                acting = false
            }
        }, enabled = !acting) { Text("Comprobar conexión de Codex") }
        if (acting) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank() || account.error.isNotBlank()) Text(error.ifBlank { account.error }, color = MaterialTheme.colorScheme.error)
    }
}
