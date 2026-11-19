package org.campana.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.campana.agent.ui.CampaignRoot

class MainActivity : ComponentActivity() {
    private val notices=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(!granted) (application as CampaignApp).graph.status.value="Los avisos están desactivados. Puedes habilitarlos desde los ajustes de la app."
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CampaignRoot((application as CampaignApp).graph,::requestNotices) }
    }
    override fun onResume() {
        super.onResume()
        (application as CampaignApp).graph.runtime.ensureStarted("owner_opened_app")
    }
    fun requestNotices() {
        if(Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) return
        val prefs=(application as CampaignApp).graph.prefs
        if(prefs.getBoolean("notice_requested",false) && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+packageName)))
            return
        }
        if(shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            android.app.AlertDialog.Builder(this).setTitle("Avisos y pausa")
                .setMessage("Los avisos muestran las tareas y permiten pausar el agente mientras opera otra app.")
                .setPositiveButton("Continuar") { _,_->prefs.edit().putBoolean("notice_requested",true).apply();notices.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .setNegativeButton("Ahora no",null).show()
        } else { prefs.edit().putBoolean("notice_requested",true).apply();notices.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
