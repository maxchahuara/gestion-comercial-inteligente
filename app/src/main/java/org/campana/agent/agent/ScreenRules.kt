package org.campana.agent.agent

import org.campana.agent.data.normalized

data class ActorEvidence(val text:String,val description:String,val editable:Boolean,val left:Int,val top:Int,val right:Int,val bottom:Int,val enabled:Boolean=true,val showingHint:Boolean=false)

object ScreenRules {
    fun unsafeControl(action:String,editable:Boolean,text:String,description:String,resource:String):Boolean {
        if(action!="click" || editable)return false
        val semantic=normalized(text+" "+description)
        val label=if(isSubmission(semantic,""))semantic else semantic+" "+resource
        return Regex("eliminar|borrar|delete|remove|comprar|purchase|pagar|payment|cerrar sesion|log out|bloquear|block|guardar|save|suscribir|subscribe|me gusta|like|seguir|follow").containsMatchIn(label)
    }
    fun captureWaitMillis(last:Long,now:Long):Long=if(last==0L)0L else (500L-(now-last)).coerceIn(0L,500L)
    data class FacebookReview(val account:String,val windowId:Int,val advancedAt:Long)
    fun facebookAdvance(pkg:String,expected:String,nodes:List<ActorEvidence>,label:String):Boolean =
        pkg=="com.facebook.katana" && normalized(label).trim() in setOf("siguiente","siguiente siguiente","next","next next") &&
            facebookComposerActor(nodes,expected) && nodes.any{it.editable && !it.showingHint && it.text.isNotBlank()} &&
            nodes.any{!it.editable && normalized(it.description.ifBlank{it.text}) in setOf("foto","photo")}
    fun facebookReviewMatches(token:FacebookReview?,pkg:String,expected:String,windowId:Int,now:Long,nodes:List<ActorEvidence>):Boolean {
        if(token==null || pkg!="com.facebook.katana" || normalized(token.account)!=normalized(expected) ||
            token.windowId!=windowId || now-token.advancedAt !in 0L..120_000L || nodes.any{it.editable})return false
        val labels=nodes.flatMap{listOf(normalized(it.text),normalized(it.description))}.toSet()
        return labels.any{it in setOf("configuracion de la publicacion","post settings")} &&
            labels.any{it in setOf("destinatarios de la publicacion","post audience")} &&
            labels.any{it in setOf("opciones de programacion","scheduling options")} &&
            nodes.any{it.enabled && listOf(it.text,it.description).any{s->normalized(s) in setOf("compartir","share","publicar","publish")}}
    }
    fun keepEditingDialog(pkg:String,kind:String,text:String,description:String,labels:List<String>):Boolean {
        fun clean(s:String)=normalized(s).trim('¿','?',' ')
        if(pkg!="com.facebook.katana" || kind!="POST" || listOf(text,description).none{clean(it) in setOf("seguir editando","keep editing")})return false
        val visible=labels.map(::clean).toSet()
        return visible.any{it in setOf("quieres terminar la publicacion mas tarde","do you want to finish your post later")} && visible.any{it in setOf("descartar publicacion","discard post")}
    }
    fun emptyWhatsAppHint(pkg:String,resource:String,text:String,nodes:List<Pair<String,String>>):Boolean =
        pkg=="com.whatsapp" && resource=="com.whatsapp:id/entry" && normalized(text) in setOf("mensaje","message") &&
            nodes.any{it.first=="com.whatsapp:id/voice_note_btn"} && nodes.none{it.first=="com.whatsapp:id/send"}
    fun emptyMessengerHint(pkg:String,text:String,description:String,labels:List<String>):Boolean =
        pkg=="com.facebook.orca" && normalized(text) in setOf("mensaje","message") && Regex("mensaje|message").containsMatchIn(normalized(description)) &&
            labels.any{normalized(it) in setOf("enviar un me gusta","send a like")} && labels.none{normalized(it) in setOf("enviar","send")}
    fun knownSubmission(pkg:String,resource:String):Boolean=when(pkg) {
        "com.whatsapp"->resource=="com.whatsapp:id/send"
        "com.instagram.android"->resource in setOf("com.instagram.android:id/row_thread_composer_button_send","com.instagram.android:id/row_thread_composer_send_button_background")
        else->false
    }
    fun searchMatches(pkg:String,query:String,expected:String):Boolean {
        val q=normalized(query);val target=normalized(expected)
        if(q==target)return q.isNotBlank()
        // WhatsApp adds this suffix to the self-chat label, but its contact search omits it.
        return pkg=="com.whatsapp" && target.endsWith(" (tu)") && q==target.removeSuffix(" (tu)") && q.isNotBlank() ||
            pkg=="com.whatsapp" && target.endsWith(" (you)") && q==target.removeSuffix(" (you)") && q.isNotBlank()
    }
    fun destinationMatches(nodes:List<Pair<String,String>>,pkg:String,expected:String,positioned:List<ActorEvidence> = emptyList()):Boolean {
        if(expected.isBlank())return false
        val match=normalized(expected)
        val allowed=when(pkg) {
            "com.whatsapp"->setOf("conversation_contact_name")
            "com.instagram.android"->setOf("action_bar_title","thread_title","thread_title_name","recipient_name")
            "com.facebook.orca"->setOf("thread_name","thread_title","chat_title","recipient_name")
            "com.facebook.katana"->setOf("composer_actor_name","actor_name","composer_profile_name")
            else->return false
        }
        val headers=nodes.count { (id,text)->id.substringAfterLast("/") in allowed && normalized(text)==match }
        if(headers==1)return true
        if(pkg=="com.facebook.orca") {
            val composer=positioned.singleOrNull{it.editable && it.enabled} ?: return false
            val back=positioned.filter{!it.editable && listOf(it.text,it.description).any{v->normalized(v) in setOf("volver","back")} && it.bottom<composer.top}.minByOrNull{it.top} ?: return false
            return positioned.any{n->!n.editable && n.left>=back.right && n.top<back.bottom && n.bottom>back.top && n.bottom<composer.top &&
                listOf(n.text,n.description).any{label->listOf(", detalles de la conversacion",", conversation details").any{suffix->val value=normalized(label);value==match+suffix || value.startsWith(match+suffix+" · ")}}}
        }
        if(pkg=="com.whatsapp") {
            val selected=nodes.count { (id,text)->id=="com.whatsapp:id/recipients" && normalized(text)==match }
            val one=nodes.any { (_,text)->Regex("(^|\\D)1 (seleccionado|selected)").containsMatchIn(normalized(text)) }
            return selected==1 && one
        }
        if(pkg=="com.instagram.android") {
            val recipients=nodes.filter { (id,_)->id=="com.instagram.android:id/recipients_container" }
            val newChat=nodes.any { (id,_)->id=="com.instagram.android:id/direct_new_chat_to_field" }
            return newChat && recipients.size==1 && normalized(recipients.single().second)==match
        }
        if(pkg=="com.facebook.katana")return facebookComposerActor(positioned,expected)
        return false
    }
    private fun facebookComposerActor(nodes:List<ActorEvidence>,expected:String):Boolean {
        // Facebook redacts view IDs. Bind the visible author to the native new-post composer,
        // rather than accepting a page name anywhere in a feed or search result.
        val fields=nodes.filter{it.editable && it.bottom>it.top}
        if(fields.size!=1)return false
        val field=fields.single()
        val titles=nodes.filter{!it.editable && normalized(it.text.ifBlank{it.description}) in setOf("nueva publicacion","crear publicacion","create post") && it.bottom<=field.top && it.bottom>it.top}
        val header=titles.minByOrNull{it.top} ?: return false
        val authors=nodes.filter{!it.editable && normalized(it.text.ifBlank{it.description})==normalized(expected) && it.top>=header.bottom && it.bottom<=field.top && it.bottom>it.top && it.right>it.left}
            .distinctBy{listOf(it.left,it.top,it.right,it.bottom)}
        return authors.size==1
    }
    fun emptyFacebookComposer(pkg:String,kind:String,expected:String,nodes:List<ActorEvidence>):Boolean {
        if(pkg!="com.facebook.katana" || kind!="POST" || !facebookComposerActor(nodes,expected))return false
        val field=nodes.single{it.editable && it.bottom>it.top}
        if(!field.showingHint)return false
        val disabledNext=nodes.any{!it.enabled && normalized(it.text.ifBlank{it.description}) in setOf("siguiente","next")}
        val media=nodes.any{!it.editable && it.top>=field.top && Regex("foto|imagen|video|photo|image").containsMatchIn(normalized(it.text+" "+it.description))}
        return disabledNext && !media
    }
    fun isComposerEntry(pkg:String,text:String,description:String,kind:String):Boolean =
        pkg=="com.facebook.katana" && kind=="POST" && listOf(text,description).any{
            normalized(it).trim('¿','?',' ') in setOf("que estas pensando","what's on your mind","escribe algo","write something")
        }
    fun isSubmission(label:String,resource:String):Boolean {
        val id=resource.substringAfterLast("/")
        return id in setOf("send","send_button","row_thread_composer_button_send","row_thread_composer_send_button_background","publish_button","post_button") ||
            Regex("^(enviar|send|publicar|publish|post|compartir|share)( \\1)?$").matches(normalized(label))
    }
    fun isNavigation(label:String,resource:String,recipient:String):Boolean {
        val value=normalized(label)
        if(recipient.isNotBlank() && value.contains(normalized(recipient)))return true
        return Regex("(^|[ ·,])(?:buscar|search|atras|back|volver|cancelar|cancel|siguiente|next|chats|direct|mensajes|messages|mensaje|message)([ ·,]|$)").containsMatchIn(value) ||
            resource.substringAfterLast("/") in setOf("menuitem_search","whatsapp_toolbar_home","wds_toolbar_nav_button","search_back")
    }
    fun writeAllowed(pkg:String,resource:String,hint:String,kind:String,destination:Boolean):Boolean {
        if(!destination)return false
        val id=resource.substringAfterLast("/")
        val h=normalized(hint)
        return when(pkg) {
            "com.whatsapp"->id=="entry" && kind!="POST"
            "com.instagram.android"->(kind!="POST" && id=="row_thread_composer_edittext") ||
                (kind=="POST" && id in setOf("caption_text_view","caption_edit_text"))
            "com.facebook.orca"->kind!="POST" && (id.contains("composer") || Regex("mensaje|message").containsMatchIn(h))
            "com.facebook.katana"->kind=="POST" && Regex("que estas pensando|what.s on your mind|escribe algo|write something").containsMatchIn(h)
            else->false
        }
    }
}
