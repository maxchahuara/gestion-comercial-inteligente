package org.campana.agent.agent

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.campana.agent.AppGraph
import org.campana.agent.data.*
import org.campana.agent.net.*
import org.campana.agent.phone.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class Toolbox(private val g:AppGraph) {
    private val pendingImages=mutableListOf<String>()
    private val writtenDrafts=mutableMapOf<String,String>()
    private val incomingReplies=mutableMapOf<String,Pair<String,String>>()
    fun bindIncomingReply(id:String,text:String,displayName:String){incomingReplies[id]=text to displayName}
    fun restoreWrittenDraft(id:String,resource:String,text:String){writtenDrafts[id+":"+resource]=text}
    fun clearTask(id:String) { writtenDrafts.keys.filter { it.startsWith(id+":") }.forEach { writtenDrafts.remove(it) };incomingReplies.remove(id) }
    fun drainImages():List<String> = pendingImages.toList().also { pendingImages.clear() }
    private data class Spec(val name:String,val description:String,val fields:Map<String,String>,val ui:Boolean=false)
    private val specs=listOf(
        Spec("campaign_read","Lee la campaña activa, recursos, interesados y tareas.",emptyMap()),
        Spec("update_brief","Actualiza varios datos de negocio. Lee antes la ficha y conserva lo que no cambió; los valores vacíos mantienen el dato previo. Nunca cambia permisos. Para editar o vaciar un solo dato usa set_brief_field.",mapOf("name" to "string","objective" to "string","offer" to "string","audience" to "string","tone" to "string","knowledge" to "string")),
        Spec("set_brief_field","Cambia un único dato de negocio pedido por el propietario. field: name, objective, offer, audience, tone o knowledge. Conserva todos los demás. value vacío borra ese dato salvo name. No cambia permisos.",mapOf("field" to "string","value" to "string")),
        Spec("propose_rules","Propone reglas para revisión humana; NO las aplica. Conserva lo no solicitado. mode: DRAFT, AUTO o REACTIVE. REACTIVE solo responde mensajes directos entrantes verificados, nunca inicia conversaciones ni publica; exige auto_reply true, allow_publishing false. channels por coma; recipients nombres/números exactos por línea; solo REACTIVE admite * para responder a cualquiera que escriba directamente, si el dueño lo pidió. horas 0..23 (iguales sin silencio).",mapOf("channels" to "string","mode" to "string","recipients" to "string","account_identity" to "string","daily_limit" to "integer","quiet_start" to "integer","quiet_end" to "integer","auto_reply" to "boolean","allow_publishing" to "boolean")),
        Spec("cancel_task","Cancela solo una tarea pendiente de esta campaña cuando el propietario lo solicita. Nunca cancela ni reintenta resultados presentados o inciertos.",mapOf("task_id" to "string")),
        Spec("resume_pending_task","Solo si el propietario lo pide: continúa una tarea pendiente revisada de esta campaña sin crear otra. Conserva destino, adjunto, instrucción y hora. Exige reglas actuales válidas y cero intentos de envío registrados. Nunca reintenta una tarea presentada, en curso o incierta.",mapOf("task_id" to "string")),
        Spec("search_knowledge","Busca información aprobada y texto de documentos locales.",mapOf("query" to "string")),
        Spec("create_task","Programa una tarea local; canal WHATSAPP, INSTAGRAM, MESSENGER o FACEBOOK; tipo MESSAGE, REPLY, POST o ATTACHMENT.",mapOf("kind" to "string","channel" to "string","instruction" to "string","recipient" to "string","asset_id" to "string","delay_minutes" to "integer")),
        Spec("remember_lead","Registra un interesado en la campaña.",mapOf("name" to "string","channel" to "string","contact" to "string","notes" to "string","stage" to "string")),
        Spec("generate_campaign_image","Genera una imagen original con GPT Image cuando el propietario pide una pieza visual. Guarda un recurso para revisar. El prompt describe solo el arte; redacta la descripción del post por separado.",mapOf("prompt" to "string")),
        Spec("read_image","Adjunta al contexto visual una imagen local de esta campaña para analizarla con el modelo seleccionado.",mapOf("asset_id" to "string")),
        Spec("open_channel","Abre la app social indicada. Solo las seleccionadas en esta campaña.",mapOf("channel" to "string"),true),
        Spec("observe_screen","Observa la pantalla accesible actual. Usa el snapshot y los IDs para la siguiente acción.",emptyMap(),true),
        Spec("capture_screen","Adjunta una captura de la app social de esta tarea al contexto visual. Solo si el modelo admite imágenes.",emptyMap(),true),
        Spec("screen_action","Opera un nodo observado. action: click, write, scroll (avanzar) o scroll_back (retroceder). Puedes pulsar una etiqueta: se usa su contenedor pulsable más cercano. text vacío salvo escritura. El destino se valida antes de envíos.",mapOf("snapshot_id" to "string","node_id" to "integer","action" to "string","text" to "string"),true),
        Spec("share_attachment","Prepara el adjunto fijo de la tarea. En POST de Facebook usa esta herramienta primero: restaura Seguir editando si aparece el diálogo de terminar más tarde, verifica el autor y solo puede cerrar/descartar un compositor realmente vacío antes de abrir el cartel en el feed. Nunca sustituye un borrador con contenido. caption es solo el texto público. Después observa la imagen y comprueba el autor antes de publicar.",mapOf("caption" to "string"),true)
    )
    private val postLinkSpec=Spec("receive_post_link","Lee la URL que la app recibió mediante su receptor nativo de enlaces durante esta inspección. El receptor solo se habilita al verificar en pantalla el autor, texto y foto del post. La tarea no permite publicar, enviar a contactos ni usar el portapapeles.",emptyMap(),true)
    private val backSpec=Spec("navigate_back","Vuelve a la pantalla anterior de la app autorizada usando Atrás de Android. No envía ni publica.",emptyMap(),true)
    private val contactSpec=Spec("verify_incoming_contact","Comprueba el nombre y número del perfil nativo del contacto frente a los números ya autorizados. Lee la evidencia directamente de la pantalla; no acepta nombres o números declarados por el modelo. Solo para verificar la conversación entrante actual. No envía mensajes ni amplía permisos.",emptyMap(),true)
    private val accountSpec=Spec("verify_messaging_account","Comprueba directamente en Menú el nombre del perfil activo frente a la fanpage de esta campaña. No basta que aparezca en una lista o un chat. Si seleccionaste la fanpage desde el selector de perfiles, vuelve a mostrar su Menú para verificarla. No autoriza respuestas desde la cuenta personal.",emptyMap(),true)
    private val noticeSpec=Spec("verify_page_notifications","Comprueba en la bandeja de la fanpage ya verificada que no siga el aviso de notificaciones desactivadas. Si aparece, solo esta preparación permite pulsar Activar en ese aviso. No permite cambiar otros ajustes ni enviar mensajes.",emptyMap(),true)
    private val inboxSpec=Spec("inspect_inbox_thread","Verifica el último mensaje real abierto desde la fanpage y encola una respuesta separada de campaña si está pendiente. El código lee remitente, texto y fecha; el modelo no los declara. No envía dentro de esta revisión.",emptyMap(),true)
    private val voiceSpec=Spec("receive_audio","Comprueba si la app ya recibió el archivo real de la nota de voz mediante Compartir. No transcribe lo que imagines escuchar ni declara un archivo inexistente.",emptyMap(),true)
    fun schema(ui:Boolean,kind:String?=null):JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type","namespace");put("name","campana");put("description","Herramientas locales del agente de campañas.")
            putJsonArray("tools") {
                (specs+postLinkSpec+backSpec+contactSpec+accountSpec+noticeSpec+inboxSpec+voiceSpec).filter { !it.ui || ui }.filter { !ui || it.ui || it.name in setOf("campaign_read","search_knowledge") }
                    .filter{if(kind=="READ_INBOX")it.name in setOf("observe_screen","capture_screen","screen_action","navigate_back","inspect_inbox_thread","verify_messaging_account") else if(kind=="PREPARE_INBOX")it.name in setOf("observe_screen","capture_screen","screen_action","navigate_back","verify_messaging_account","verify_page_notifications") else if(kind=="READ_ACCOUNT")it.name in setOf("observe_screen","capture_screen","screen_action","navigate_back","verify_messaging_account") else if(kind=="VERIFY_CONTACT")it.name in setOf("observe_screen","capture_screen","screen_action","verify_incoming_contact","navigate_back") else if(kind=="READ_POST")it.name in setOf("open_channel","observe_screen","capture_screen","screen_action","receive_post_link","campaign_read","navigate_back") else it.name !in setOf("receive_post_link","verify_incoming_contact","verify_messaging_account","verify_page_notifications","inspect_inbox_thread")}.forEach { s ->
                    if(kind=="READ_AUDIO" && s.name !in setOf("observe_screen","capture_screen","screen_action","navigate_back","receive_audio"))return@forEach
                    if(kind!="READ_AUDIO" && s.name=="receive_audio")return@forEach
                    add(buildJsonObject {
                        put("type","function");put("name",s.name);put("description",s.description);put("strict",true)
                        putJsonObject("parameters") {
                            put("type","object");put("additionalProperties",false)
                            putJsonObject("properties") { s.fields.forEach { (k,v)->putJsonObject(k) { put("type",v) } } }
                            putJsonArray("required") { s.fields.keys.forEach { add(it) } }
                        }
                    })
                }
            }
        })
    }
    suspend fun execute(name:String,a:JsonObject,campaignId:String,task:AgentTask?):String {
        val spec=(specs+postLinkSpec+backSpec+contactSpec+accountSpec+noticeSpec+inboxSpec+voiceSpec).firstOrNull { it.name==name } ?: error("Herramienta desconocida.")
        val voicePhase=task!=null && g.voiceExport.active(task)
        if(voicePhase)require(name in setOf("observe_screen","capture_screen","screen_action","navigate_back","receive_audio"))
        if(name=="receive_audio")require(voicePhase)
        if(task?.kind=="READ_POST")require(name in setOf("open_channel","observe_screen","capture_screen","screen_action","receive_post_link","campaign_read","navigate_back"))
        if(task?.kind=="VERIFY_CONTACT")require(name in setOf("observe_screen","capture_screen","screen_action","verify_incoming_contact","navigate_back"))
        if(task?.kind=="READ_ACCOUNT")require(name in setOf("observe_screen","capture_screen","screen_action","navigate_back","verify_messaging_account"))
        if(task?.kind=="PREPARE_INBOX")require(name in setOf("observe_screen","capture_screen","screen_action","navigate_back","verify_messaging_account","verify_page_notifications"))
        if(task?.kind=="READ_INBOX")require(name in setOf("observe_screen","capture_screen","screen_action","navigate_back","inspect_inbox_thread","verify_messaging_account"))
        if(!voicePhase && task?.notificationKey?.startsWith("ui-inbox:")==true)require(name in setOf("observe_screen","capture_screen","screen_action","campaign_read","search_knowledge")){"La respuesta verificada permanece en su conversación de origen."}
        require(!spec.ui || task!=null) { "El control visual requiere una tarea con destino fijo." }
        if(spec.ui)check(g.prefs.getBoolean("ui_enabled",false)) { "El propietario desactivó el control visual." }
        require(a.keys==spec.fields.keys) { "Argumentos incompletos o desconocidos." }
        spec.fields.forEach { (key,type)->
            val value=a[key] as? JsonPrimitive ?: error("Argumento inválido: "+key)
            require(when(type){"integer"->value.intOrNull!=null && !value.isString;"boolean"->value.booleanOrNull!=null && !value.isString;else->value.isString}) { "Tipo de argumento inválido: "+key }
        }
        if(task!=null) require(spec.ui || name in setOf("campaign_read","search_knowledge"))
        val c=g.dao.campaign(campaignId) ?: error("Campaña no encontrada.")
        if(task!=null || spec.ui)check(!g.paused.value) { "El agente está pausado." }
        if(spec.ui)withContext(Dispatchers.Main){checkNotNull(PhoneAccessibilityService.instance).requireControl()}
        return when(name) {
            "campaign_read" -> buildJsonObject {
                put("phone_local_time",java.time.ZonedDateTime.now().toString())
                put("campaign",json.encodeToJsonElement(c));put("assets",json.encodeToJsonElement(g.dao.assets(c.id)))
                put("leads",json.encodeToJsonElement(g.dao.leads(c.id)));put("tasks",json.encodeToJsonElement(g.dao.tasks(c.id).take(25)))
            }.toString()
            "update_brief" -> {
                require(a.str("name").isNotBlank());require(a.values.sumOf { it.toString().length }<50_000)
                g.db.withTransaction {
                    val latest=checkNotNull(g.dao.campaign(c.id))
                    g.dao.save(latest.copy(name=a.str("name").take(100),objective=a.str("objective").ifBlank { latest.objective }.take(4000),offer=a.str("offer").ifBlank { latest.offer }.take(4000),
                        audience=a.str("audience").ifBlank { latest.audience }.take(2000),tone=a.str("tone").ifBlank { latest.tone }.take(1000),knowledge=a.str("knowledge").ifBlank { latest.knowledge }.take(30_000),updatedAt=System.currentTimeMillis()))
                }
                "Ficha actualizada. Las reglas de autonomía no cambiaron."
            }
            "set_brief_field" -> {
                val value=a.str("value")
                g.db.withTransaction {
                    val current=checkNotNull(g.dao.campaign(c.id))
                    val changed=when(a.str("field")) {
                        "name"->{require(value.isNotBlank());current.copy(name=value.take(100))}
                        "objective"->current.copy(objective=value.take(4000))
                        "offer"->current.copy(offer=value.take(4000))
                        "audience"->current.copy(audience=value.take(2000))
                        "tone"->current.copy(tone=value.take(1000))
                        "knowledge"->current.copy(knowledge=value.take(30000))
                        else->error("Ese dato no se puede modificar con esta herramienta.")
                    }
                    g.dao.save(changed.copy(updatedAt=System.currentTimeMillis()))
                }
                "Dato guardado. Los demás datos y las autorizaciones se conservaron."
            }
            "propose_rules" -> g.conversations.propose(c.id,CampaignRules(a.str("channels"),a.str("mode"),a.str("recipients"),a.str("account_identity"),
                a.getValue("daily_limit").jsonPrimitive.int,a.getValue("quiet_start").jsonPrimitive.int,a.getValue("quiet_end").jsonPrimitive.int,
                a.getValue("auto_reply").jsonPrimitive.boolean,a.getValue("allow_publishing").jsonPrimitive.boolean))
            "cancel_task" -> {
                val pending=checkNotNull(g.dao.task(a.str("task_id"))) { "La tarea no existe." }
                require(pending.campaignId==c.id) { "La tarea pertenece a otra campaña." }
                if(g.dao.cancelPending(pending.id,System.currentTimeMillis())==1)"Tarea pendiente cancelada. No se deshicieron acciones ya presentadas."
                else "La tarea ya comenzó, fue resuelta o tiene resultado incierto. No se modificó; revisa su estado."
            }
            "resume_pending_task" -> {
                val pending=g.db.withTransaction {
                    val t=checkNotNull(g.dao.task(a.str("task_id"))) { "La tarea no existe." }
                    require(t.campaignId==c.id && t.status in setOf("DRAFT","WAITING_OWNER","WAITING_NETWORK","WAITING_UNLOCK","WAITING_PERMISSION")) { "La tarea no admite continuación segura." }
                    check(g.dao.allReceipts(c.id).none{it.taskId==t.id && it.action=="external_submit"}) { "Ya hubo un intento de envío. Comprueba el resultado; no se reintentará." }
                    val current=checkNotNull(g.dao.campaign(c.id))
                    val since=LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val decision=Policy.evaluate(current,t.channel,t.kind,t.recipient,g.paused.value,LocalTime.now().hour,g.dao.submissions(c.id,since),incomingVerified=t.kind=="REPLY" && t.notificationKey.isNotBlank() && t.notificationFingerprint.isNotBlank() && t.assetId.isBlank())
                    check(decision.allowed){decision.reason}
                    if(t.notificationKey.isBlank())check(g.prefs.getBoolean("ui_enabled",false)){"Activa el control de apps en Ajustes."}
                    check(g.dao.queueReviewed(t.id,System.currentTimeMillis())==1)
                    t.copy(status="QUEUED")
                }
                org.campana.agent.jobs.TaskWorker.schedule(g.context,pending,reviewed=true)
                "Se programó la misma tarea pendiente según las reglas vigentes. No se creó un duplicado ni se confirmó entrega."
            }
            "search_knowledge" -> {
                val query=normalized(a.str("query"))
                val sources=g.dao.assets(c.id).filter { query.isBlank() || normalized(it.extractedText+" "+it.name).contains(query) }
                buildJsonObject { put("approved",c.knowledge.take(18000));putJsonArray("sources") {
                    sources.take(5).forEach { asset->add(buildJsonObject { put("name",asset.name);put("text",asset.extractedText.take(12000)) }) }
                } }.toString()
            }
            "create_task" -> {
                val channel=Channel.valueOf(a.str("channel"))
                require(channel in c.channelList());require(a.str("kind") in setOf("MESSAGE","REPLY","POST","ATTACHMENT"))
                val assetId=a.str("asset_id")
                if(assetId.isNotBlank()) require(g.dao.asset(assetId)?.campaignId==c.id)
                val delay=a.number("delay_minutes");require(delay in 0..525600)
                val next=AgentTask(campaignId=c.id,channel=channel.name,kind=a.str("kind"),instruction=a.str("instruction").take(6000),
                    recipient=a.str("recipient").take(200),assetId=assetId,dueAt=System.currentTimeMillis()+delay*60_000,
                    status=if(c.mode=="AUTO")"QUEUED" else "DRAFT")
                g.enqueue(next)
                buildJsonObject {
                    json.encodeToJsonElement(next).jsonObject.forEach { (key,value)->put(key,value) }
                    put("due_local",java.time.Instant.ofEpochMilli(next.dueAt).atZone(ZoneId.systemDefault()).toString())
                }.toString()
            }
            "remember_lead" -> {
                require(a.str("name").isNotBlank());require(a.values.sumOf { it.toString().length }<10000)
                val channel=Channel.valueOf(a.str("channel"));require(channel in c.channelList())
                val existing=g.dao.leads(c.id).firstOrNull { it.channel==channel.name && normalized(it.contact)==normalized(a.str("contact")) }
                val lead=Lead(id=existing?.id ?: newId(),campaignId=c.id,name=a.str("name"),channel=channel.name,contact=a.str("contact"),notes=a.str("notes"),stage=a.str("stage"))
                g.dao.save(lead);json.encodeToString(lead)
            }
            "generate_campaign_image" -> {
                require(task == null) { "La generación visual se solicita en el chat del propietario." }
                json.encodeToString(CampaignImageGeneration.generate(g,c,a.str("prompt")))
            }
            "read_image" -> {
                require(task==null)
                val asset=g.dao.asset(a.str("asset_id")) ?: error("Imagen no encontrada.")
                require(asset.campaignId==c.id && asset.mime.startsWith("image/"))
                val bytes=withContext(Dispatchers.IO) {
                    val file=g.assets.file(asset)
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(file.path,bounds)
                    require(bounds.outWidth>0 && bounds.outHeight>0)
                    var sample=1
                    while(bounds.outWidth/sample>1280 || bounds.outHeight/sample>1280)sample*=2
                    val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("Imagen ilegible.")
                    val out=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,75,out);bitmap.recycle();out.toByteArray()
                }
                pendingImages+="data:image/jpeg;base64,"+java.util.Base64.getEncoder().encodeToString(bytes)
                "Imagen local adjunta al contexto visual: "+asset.name
            }
            "open_channel" -> {
                val channel=Channel.valueOf(a.str("channel"));if(task?.kind=="READ_POST")g.postLinks.target(task) else require(channel in c.channelList())
                if(task!=null) require(task.channel==channel.name)
                check(g.prefs.getBoolean("ui_enabled",false)) { "Activa el control de apps en Conexiones." }
                val intent=SocialApps.launchIntent(g.context,channel) ?: error("No se encontró una entrada pública para abrir la app.")
                withContext(Dispatchers.Main) { val service=checkNotNull(PhoneAccessibilityService.instance);service.requireControl();service.resetFacebookReview();g.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                delay(900);"Aplicación abierta. Observa la pantalla antes de continuar."
            }
            "verify_incoming_contact" -> g.incomingIdentities.verify(checkNotNull(task).also{require(it.kind=="VERIFY_CONTACT")})
            "verify_messaging_account" -> g.messengerAccounts.verifyCurrent(checkNotNull(task))
            "verify_page_notifications" -> g.messengerAccounts.verifyNotifications(checkNotNull(task))
            "inspect_inbox_thread" -> g.inbox.inspect(checkNotNull(task))
            "receive_audio" -> "{\"file_received\":"+g.voiceExport.ready(checkNotNull(task).id)+"}"
            "navigate_back" -> {
                require(task!=null)
                val service=checkNotNull(PhoneAccessibilityService.instance)
                if(voicePhase)return g.voiceExport.back(task).toString()
                if(task.kind=="READ_INBOX")return g.inbox.back(task,service).toString()
                withContext(Dispatchers.Main) {
                    val state=service.observe()
                    require(state.packageName==Channel.valueOf(task.channel).packageName || (task.kind=="READ_POST" && g.postLinks.extraSurface(state.packageName)))
                    service.navigateBack(state.packageName)
                }.toString()
            }
            "observe_screen" -> {
                require(task!=null)
                val state=withContext(Dispatchers.Main) { checkNotNull(PhoneAccessibilityService.instance) { "Accesibilidad no disponible." }.observe() }
                require(Channel.forPackage(state.packageName)?.name==task.channel || (task.kind=="READ_POST" && g.postLinks.extraSurface(state.packageName)) || (voicePhase && g.voiceExport.extraSurface(state.packageName)))
                json.encodeToString(state)
            }
            "capture_screen" -> {
                require(task!=null)
                val service=checkNotNull(PhoneAccessibilityService.instance)
                val state=withContext(Dispatchers.Main) { service.observe() }
                require(state.packageName==Channel.valueOf(task.channel).packageName || (task.kind=="READ_POST" && g.postLinks.extraSurface(state.packageName)) || (voicePhase && g.voiceExport.extraSurface(state.packageName)))
                val bytes=withContext(Dispatchers.Main){service.requireControl();service.screenshot(state.windowId)};check(bytes.isNotEmpty()) { "No se pudo capturar esta ventana." }
                pendingImages+="data:image/jpeg;base64,"+java.util.Base64.getEncoder().encodeToString(bytes)
                json.encodeToString(state)
            }
            "screen_action" -> {
                require(task!=null)
                val service=checkNotNull(PhoneAccessibilityService.instance)
                val state=withContext(Dispatchers.Main) { service.remembered(a.str("snapshot_id"),if(task.kind=="READ_POST")60_000 else 15_000) }
                require(state.packageName==Channel.valueOf(task.channel).packageName || (task.kind=="READ_POST" && g.postLinks.extraSurface(state.packageName)) || (voicePhase && g.voiceExport.extraSurface(state.packageName)))
                val requested=a.number("node_id").toInt()
                val node=state.nodes.firstOrNull { it.id==requested } ?: error("Elemento no encontrado.")
                val action=a.str("action")
                if(voicePhase){check(a.str("text").isBlank());val done=g.voiceExport.act(task,service,state,node,action);delay(if(action=="long_click")950 else 650);return "{\"performed\":$done,\"external_submit\":false}"}
                if(task.kind=="READ_INBOX") {
                    check(a.str("text").isBlank())
                    val performed=g.inbox.act(task,service,state,node,action)
                    delay(600)
                    return buildJsonObject{put("performed",performed);put("read_only",true);put("external_submit",false)}.toString()
                }
                if(task.kind in setOf("READ_ACCOUNT","PREPARE_INBOX")) {
                    check(a.str("text").isBlank())
                    val performed=g.messengerAccounts.act(task,service,state,node,action)
                    delay(500)
                    return buildJsonObject{put("performed",performed);put("read_only",true);put("external_submit",false)}.toString()
                }
                if(task.kind=="VERIFY_CONTACT") {
                    check(a.str("text").isBlank())
                    val performed=g.incomingIdentities.act(task,service,state,node,action)
                    delay(500)
                    return buildJsonObject{put("performed",performed);put("read_only",true);put("external_submit",false)}.toString()
                }
                if(task.kind=="READ_POST") {
                    check(a.str("text").isBlank())
                    authorize(c.id,task)
                    val performed=g.postLinks.act(task,service,state,node,action)
                    delay(600)
                    return buildJsonObject{put("performed",performed);put("read_only",true);put("external_submit",false)}.toString()
                }
                val label=normalized(node.text+" "+node.description+" "+node.resource)
                val keepEditing=action=="click" && ScreenRules.keepEditingDialog(state.packageName,task.kind,node.text,node.description,state.nodes.flatMap{listOf(it.text,it.description)})
                require(keepEditing || !ScreenRules.unsafeControl(action,node.editable,node.text,node.description,node.resource)) { "Esta acción requiere intervención humana." }
                val expected=if(task.kind=="POST")c.accountIdentity else incomingReplies[task.id]?.second ?: task.recipient
                val actors=state.nodes.map{ActorEvidence(it.text,it.description,it.editable,it.left,it.top,it.right,it.bottom,it.enabled,it.showingHint)}
                val directDestination=ScreenRules.destinationMatches(state.nodes.map { it.resource to it.text },state.packageName,expected,actors)
                val destinationVerified=directDestination || (task.kind=="POST" && task.channel=="FACEBOOK" && withContext(Dispatchers.Main){service.verifiedFacebookReview(state,expected)})
                val searching=Regex("search|query").containsMatchIn(node.resource)
                val submit=action=="click" && ScreenRules.isSubmission(node.text+" "+node.description,node.resource)
                if(task.notificationKey.startsWith("ui-inbox:")) {
                    check(action in setOf("write","scroll","scroll_back") || (action=="click" && (submit || node.editable))){"Esta respuesta solo puede escribir y enviar en la conversación entrante ya verificada."}
                    if(action=="write" || submit)g.inbox.requirePending(task)
                }
                if(action=="click") require(node.text.isNotBlank() || node.description.isNotBlank() ||
                    (submit && ScreenRules.knownSubmission(state.packageName,node.resource))) { "Control sin etiqueta ni identificador de envío validado; requiere intervención." }
                if(action=="click" && !submit)check(keepEditing || ScreenRules.isNavigation(node.text+" "+node.description,node.resource,expected) || ScreenRules.isComposerEntry(state.packageName,node.text,node.description,task.kind)) { "Control fuera del flujo autorizado de mensajes o publicaciones." }
                if(action=="write") {
                    if(incomingReplies.containsKey(task.id))check(a.str("text")==incomingReplies.getValue(task.id).first && g.incomingIdentities.uiReplySource(task)!=null && g.incomingWake.value){"La respuesta entrante debe conservar el texto generado y su procedencia verificada."}
                    if(searching) require(ScreenRules.searchMatches(state.packageName,a.str("text"),expected)) { "La búsqueda debe usar el destino autorizado. En el chat propio de WhatsApp puedes omitir el sufijo (Tú)/(You)." }
                    else {
                        val composerHint=node.hint.ifBlank { if(state.packageName=="com.facebook.orca")node.description else if(ScreenRules.isComposerEntry(state.packageName,node.text,"",task.kind))node.text else "" }
                        check(ScreenRules.writeAllowed(state.packageName,node.resource,composerHint,task.kind,destinationVerified)) { "Este campo no es un compositor autorizado para la tarea." }
                        val emptyWhatsAppHint=ScreenRules.emptyWhatsAppHint(state.packageName,node.resource,node.text,state.nodes.map{it.resource to it.text}) || ScreenRules.emptyMessengerHint(state.packageName,node.text,node.description,state.nodes.flatMap{listOf(it.text,it.description)})
                        if(node.text.isNotBlank() && !node.showingHint && !emptyWhatsAppHint && writtenDrafts[task.id+":"+node.resource]!=node.text)
                            error("Hay un borrador previo. El propietario debe revisarlo antes de sustituirlo.")
                    }
                }
                if(submit) {
                    if(incomingReplies.containsKey(task.id)) {
                        check(g.incomingIdentities.uiReplySource(task)!=null && g.incomingWake.value)
                        val input=state.nodes.singleOrNull{it.editable && it.enabled}
                        val approved=incomingReplies.getValue(task.id).first
                        check(input!=null && !input.showingHint && input.text==approved && writtenDrafts[task.id+":"+input.resource]==approved){"El compositor no contiene exactamente la respuesta de campaña escrita por esta tarea."}
                    }
                    check(!hasSubmitted(task.id)) { "La tarea ya presentó un envío; revisa el resultado antes de continuar." }
                    authorize(c.id,task)
                    check(destinationVerified) { "No se pudo verificar un único destino. Requiere revisión humana." }
                    g.dao.taskState(task.id,"SUBMITTING","Pulsación de envío o publicación en la app.")
                    g.dao.insert(Receipt(campaignId=c.id,taskId=task.id,action="external_submit",status="SUBMITTING",detail="Destino verificado: "+expected))
                } else authorize(c.id,task)
                val facebookNext=task.kind=="POST" && action=="click" && ScreenRules.facebookAdvance(state.packageName,expected,actors,node.text+" "+node.description)
                val destination=if(submit || facebookNext || (action=="write" && !searching))expected else ""
                val result=withContext(Dispatchers.Main) {
                    service.uncoverTarget(Rect(node.left,node.top,node.right,node.bottom))
                    service.act(state.id,requested,action,a.str("text"),destination,if(submit)incomingReplies[task.id]?.first.orEmpty() else "")
                }
                if(result && action=="write")writtenDrafts[task.id+":"+node.resource]=a.str("text")
                if(submit && !result) g.dao.taskState(task.id,"UNCERTAIN","El control de envío no confirmó la acción. Comprueba el resultado.")
                delay(500)
                if(result && facebookNext)withContext(Dispatchers.Main){service.awaitFacebookReview()}
                buildJsonObject { put("performed",result);put("delivery_confirmed",false);put("note","Observa nuevamente para comprobar el efecto.") }.toString()
            }
            "receive_post_link" -> {
                require(task?.kind=="READ_POST")
                g.postLinks.target(task)
                delay(400)
                g.postLinks.received(task.id)
            }
            "share_attachment" -> {
                require(task!=null);authorize(c.id,task);require(task.assetId.isNotBlank())
                val asset=g.dao.asset(task.assetId) ?: error("Adjunto no encontrado.");require(asset.campaignId==c.id)
                val uri=g.assets.uri(asset)
                val intent=Intent(Intent.ACTION_SEND).apply {
                    type=asset.mime;setPackage(Channel.valueOf(task.channel).packageName)
                    putExtra(Intent.EXTRA_STREAM,uri);putExtra(Intent.EXTRA_TEXT,a.str("caption").take(4000))
                    clipData=ClipData.newRawUri(asset.name,uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                SocialApps.targetShare(g.context,Channel.valueOf(task.channel),task.kind,intent)
                if(task.channel=="FACEBOOK" && task.kind=="POST") {
                    val service=checkNotNull(PhoneAccessibilityService.instance)
                    withContext(Dispatchers.Main){service.prepareFacebookShare(c.accountIdentity)}
                }
                withContext(Dispatchers.Main) { checkNotNull(PhoneAccessibilityService.instance).requireControl();g.context.startActivity(intent) };delay(800)
                "Archivo presentado al selector de la app. Debes comprobar el destinatario; no se ha confirmado ningún envío."
            }
            else -> error("Herramienta no implementada.")
        }
    }
    private suspend fun hasSubmitted(id:String)=g.dao.task(id)?.status in setOf("SUBMITTING","SUBMITTED","UNCERTAIN")
    private suspend fun authorize(campaignId:String,task:AgentTask) {
        check(task.campaignId==campaignId)
        g.engine.authorized(task)
        if(task.kind=="REPLY" && task.notificationKey.isNotBlank())check((incomingReplies.containsKey(task.id) || g.voiceExport.active(task)) && g.incomingIdentities.uiReplySource(task)!=null){"La respuesta visual necesita su procedencia nativa y su texto generado."}
        check(g.prefs.getBoolean("ui_enabled",false)) { "El propietario desactivó el control." }
    }
}
