package org.campana.agent.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.campana.agent.data.Channel

object SocialApps {
    fun targetShare(context:Context,channel:Channel,kind:String,intent:Intent):Intent {
        if(channel==Channel.FACEBOOK && kind=="POST") {
            // Installed Facebook exposes distinct share targets for groups, stories and profile photos.
            // Only the native feed-composer alias is valid for this task; never guess a chooser row.
            val component=ComponentName(channel.packageName,"com.facebook.composer.shareintent.ImplicitShareIntentHandlerDefaultAlias")
            val candidates=context.packageManager.queryIntentActivities(intent,0)
            check(candidates.any { r->r.activityInfo?.let{it.packageName==component.packageName && it.name==component.className && it.exported && it.enabled && it.applicationInfo.enabled}==true }) {
                "No se encontró el compositor nativo de publicaciones de Facebook. Revisa la versión instalada."
            }
            intent.component=component
        }
        return intent
    }
    fun installed(context:Context,channel:Channel):Boolean = runCatching {
        context.packageManager.getApplicationInfo(channel.packageName,0).enabled
    }.getOrDefault(false)

    fun launchIntent(context:Context,channel:Channel):Intent? {
        context.packageManager.getLaunchIntentForPackage(channel.packageName)?.let { return it }
        // Public Instagram alias observed on the test device; validate before using it.
        if(channel!=Channel.INSTAGRAM)return null
        val component=ComponentName(channel.packageName,"com.instagram.android.activity.MainTabActivity")
        val info=runCatching { context.packageManager.getActivityInfo(component,0) }.getOrNull() ?: return null
        if(!info.exported || !info.enabled || !info.applicationInfo.enabled || info.packageName!=channel.packageName)return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
    }
}
