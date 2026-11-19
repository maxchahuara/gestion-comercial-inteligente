package org.campana.agent.agent

import org.campana.agent.data.normalized
import java.net.URI
import org.campana.agent.phone.ScreenNode

object PostLinkRules {
    fun liveNode(nodes:List<ScreenNode>,observed:ScreenNode):ScreenNode?=nodes.singleOrNull {
        it.id==observed.id && it.scrollable==observed.scrollable && it.clickable==observed.clickable &&
            it.editable==observed.editable && it.enabled==observed.enabled &&
            it.text==observed.text && it.description==observed.description && it.resource==observed.resource &&
            it.left==observed.left && it.top==observed.top && it.right==observed.right && it.bottom==observed.bottom
    }
    fun facebookUrl(text:String):String? {
        if(text.length>4000)return null
        val urls=Regex("https://[^\\s<>]+",RegexOption.IGNORE_CASE).findAll(text).map{it.value.trimEnd('.',',',')',';',']')}.toList()
        if(urls.size!=1)return null
        val raw=urls.single()
        val uri=runCatching{URI(raw)}.getOrNull() ?: return null
        if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 || uri.fragment!=null || uri.host?.lowercase() !in setOf("facebook.com","www.facebook.com","m.facebook.com"))return null
        val path=uri.path.orEmpty()
        return raw.takeIf{Regex("^/share/(?:p/)?[A-Za-z0-9]+/?$").matches(path) || path.contains("/posts/") || path in setOf("/permalink.php","/photo.php","/photo/")}
    }
    fun feedShare(label:String)=normalized(label).startsWith("boton \"compartir\".") && normalized(label).contains("compartir la publicacion")
    fun verifiedPost(nodes:List<ActorEvidence>,account:String,caption:String,share:ActorEvidence):Boolean {
        if(caption.isBlank() || !feedShare(share.description.ifBlank{share.text}))return false
        val bodies=nodes.filter{!it.editable && normalized(it.text)==normalized(caption) && it.bottom>it.top && it.bottom<=share.top}.distinctBy{listOf(it.left,it.top,it.right,it.bottom)}
        if(bodies.size!=1)return false
        val body=bodies.single()
        val authors=nodes.filter{!it.editable && normalized(it.text)==normalized(account) && it.bottom<=body.top && it.bottom>it.top}.distinctBy{listOf(it.left,it.top,it.right,it.bottom)}
        val author=authors.maxByOrNull{it.top} ?: return false
        if(body.top-author.bottom>700)return false
        val photos=nodes.filter{normalized(it.description.substringBefore(" · ")) in setOf("foto","photo") && it.top>=body.bottom && it.bottom<=share.top && it.bottom>it.top}
        val gallery=nodes.any{Regex("^[0-9]+ (?:of|de) [0-9]+$").matches(normalized(it.text)) && it.bottom<=author.top}
        val mediaVerified=if(gallery)nodes.none{it.editable} else photos.size==1
        return mediaVerified && nodes.none{normalized(it.text)==normalized(account) && it.top>body.bottom && it.bottom<=share.top}
    }
    fun chooserPackage(pkg:String)=pkg in setOf("android","com.android.intentresolver","com.samsung.android.app.sharelive","com.samsung.android.app.sharechooser")
    fun readableSurface(pkg:String,ownPackage:String,pending:Boolean)=chooserPackage(pkg) || (pending && pkg==ownPackage)
    private fun shareLabel(label:String)=normalized(label).trim().replace(Regex("\\s*·\\s*")," ").replace(Regex("\\s+")," ")
    fun ownShareTarget(label: String, targetLabel: String, appLabel: String = targetLabel): Boolean {
        val target = shareLabel(targetLabel)
        val app = shareLabel(appLabel)
        return shareLabel(label) in setOf(target, "$target $target", "$target $app", "$app $target")
    }
    fun navigation(pkg:String,label:String,pending:Boolean,appLabel:String):Boolean {
        val s=shareLabel(label)
        if(chooserPackage(pkg))return pending && (ownShareTarget(label,appLabel) || s in setOf("mas","mas opciones","mas aplicaciones","more","more options","more apps"))
        if(pkg!="com.facebook.katana")return false
        if(s.isBlank())return false
        // Effect boundaries, not a scripted route: the model chooses the visible navigation.
        if(Regex("enviar|send|publicar|publish|compartir ahora|share now|copiar|copy|editar|edit|eliminar|delete|borrar|remove|descartar|discard|guardar|save|comprar|purchase|pagar|payment|anunciar|promocionar|boost|me gusta|like|seguir|follow|reaccion|reaction|suscribir|subscribe|archiv|papelera|trash|bloquear|block|configuracion|settings|administrar|manage|cerrar sesion|log out").containsMatchIn(s))return false
        if(Regex("compartir|share").containsMatchIn(s))return pending && Regex("opciones|options|compartir en|share to|share via").containsMatchIn(s)
        return true
    }
}
