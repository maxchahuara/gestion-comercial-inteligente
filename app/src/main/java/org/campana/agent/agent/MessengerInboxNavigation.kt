package org.campana.agent.agent

import org.campana.agent.data.normalized
import org.campana.agent.phone.ScreenNode
import org.campana.agent.phone.ScreenState

/** Read-only shortcuts use current native roles; unknown layouts return to visual reasoning. */
object MessengerInboxNavigation {
    fun labels(n:ScreenNode)=listOf(n.text,n.description,n.nativeDescription).map(::normalized)
    fun exact(nodes:List<ScreenNode>,label:String):ScreenNode? = nodes.filter{it.enabled && it.clickable && labels(it).any{v->v==normalized(label)}}.minByOrNull{(it.right-it.left)*(it.bottom-it.top)}
    fun menu(nodes:List<ScreenNode>)=nodes.any{!it.editable && labels(it).any{v->v=="menu"}}
    fun rows(state:ScreenState):List<ScreenNode> {
        if(state.packageName!="com.facebook.orca" || !MessengerAccountRules.pageInbox(state.nodes))return emptyList()
        val edge=state.nodes.filter{labels(it).any{v->v=="no respondidos"}}.minOfOrNull{it.bottom} ?: return emptyList()
        val bottom=state.nodes.filter{it.clickable && labels(it).any{v->MessengerAccountRules.chatTab(v) || MessengerAccountRules.menuTab(v)}}.minOfOrNull{it.top} ?: return emptyList()
        val width=state.nodes.maxOfOrNull{it.right} ?: return emptyList()
        return state.nodes.filter{it.enabled && it.clickable && !it.editable && it.top>=edge && it.bottom<=bottom && it.right-it.left>width*.5 &&
            it.nativeDescription.endsWith(", Open conversation")}.sortedBy{it.top}
    }
    fun children(state:ScreenState,row:ScreenNode)=state.nodes.filter{!it.editable && it.left>=row.left && it.right<=row.right && it.top>=row.top && it.bottom<=row.bottom}
    fun previousYear(state:ScreenState,row:ScreenNode,year:Int)=children(state,row).any{n->
        Regex("^\\s*·.*\\b(20\\d{2})\\b").find(n.text)?.groupValues?.get(1)?.toInt()?.let{it<year}==true
    }
    fun ownPreview(state:ScreenState,row:ScreenNode)=children(state,row).any{n->
        Regex("^(tu|you):\\s").containsMatchIn(normalized(n.text))
    }
    fun signature(state:ScreenState,row:ScreenNode):String = listOf(row.nativeDescription,children(state,row).joinToString("\u0001"){it.text}).joinToString("\u0000")
}
