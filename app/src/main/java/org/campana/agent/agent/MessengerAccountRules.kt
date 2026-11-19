package org.campana.agent.agent

import org.campana.agent.data.normalized

object MessengerAccountRules {
    fun chatTab(label:String)=Regex("^(chats|mensajes)(,|$)").containsMatchIn(normalized(label)) || Regex("^pestana [\"“](chats|mensajes)[\"”](,|$)").containsMatchIn(normalized(label))
    fun selectorLabel(label:String)=Regex("^(cambiar de perfil|cambiar de cuenta|cambiar cuenta|switch profile|switch accounts?|seleccionar cuenta|select account)([ ·,:]|$)").containsMatchIn(normalized(label))
    fun pageChoice(label:String,expected:String):Boolean {
        if(expected.isBlank())return false
        val value=normalized(label).replace(Regex("[·,]")," ").replace(Regex("\\s+")," ")
        val name=normalized(expected)
        val parts=normalized(label).split(" · ").map{it.trim()}
        val switch=parts.size<=3 && parts.first() in setOf("cambiar a $name","switch to $name") && parts.drop(1).all{it in setOf(name,"pagina","page")}
        return switch || value==name || value=="$name pagina" || value=="$name page"
    }
    fun activePageMenu(nodes:List<org.campana.agent.phone.ScreenNode>,expected:String):Boolean {
        if(expected.isBlank())return false
        val menu=nodes.filter{!it.editable && listOf(it.text,it.description).any{v->normalized(v)=="menu"} && it.bottom>it.top}.minByOrNull{it.bottom-it.top} ?: return false
        val switch=nodes.filter{!it.editable && listOf(it.text,it.description).any{v->normalized(v).contains("cambiar de perfil") || normalized(v).contains("switch profile")}}
        return switch.any{hint->nodes.any{n->!n.editable && listOf(n.text,n.description).flatMap{it.split(Regex("\\s*[·,]\\s*"))}.any{org.campana.agent.data.sameContact(it,expected)} && n.top>=menu.bottom && n.bottom<=hint.bottom && n.bottom>n.top}}
    }
    fun pageAlertsDisabled(nodes:List<org.campana.agent.phone.ScreenNode>)=nodes.any{n->listOf(n.text,n.description).any{normalized(it).contains("todas las notificaciones de esta pagina estan desactivadas") || normalized(it).contains("all notifications for this page are turned off")}}
    fun activatePageAlerts(nodes:List<org.campana.agent.phone.ScreenNode>,target:org.campana.agent.phone.ScreenNode):Boolean {
        if(listOf(target.text,target.description).none{normalized(it) in setOf("activar","turn on")})return false
        return nodes.any{n->pageAlertsDisabled(listOf(n)) && target.top<n.bottom+40 && target.bottom>n.top-40}
    }
    fun pageInbox(nodes:List<org.campana.agent.phone.ScreenNode>):Boolean {
        val labels=nodes.filter{!it.editable}.flatMap{listOf(it.text,it.description)}.map{normalized(it)}
        return listOf("todo","no leidos","no respondidos").all{tab->labels.any{it==tab || Regex("^pestana [\"“]?"+Regex.escape(tab)+"[\"”]?([ ,·]|$)").containsMatchIn(it)}}
    }
    /** Native tab accessibility labels include the role, badge and tab position. */
    fun menuTab(label:String)=Regex("^pestana [\"“]menu[\"”](,|$)").containsMatchIn(normalized(label)) ||
        Regex("^menu tab(,|$)").containsMatchIn(normalized(label))
}
