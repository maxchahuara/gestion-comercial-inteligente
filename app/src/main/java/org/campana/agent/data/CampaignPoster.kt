package org.campana.agent.data

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Original, deterministic artwork rendered on Android; no remote image service. */
object CampaignPoster {
    fun render(title:String,slogan:String,number:String,description:String,footer:String,emblem:String):Bitmap {
        require(title.isNotBlank() && title.length<=120 && slogan.length<=150 && description.length<=220 && footer.length<=160)
        require(number.matches(Regex("[0-9]{1,3}")) && emblem in setOf("cat","none"))
        val bitmap=Bitmap.createBitmap(1080,1080,Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap)
        val cream=Color.rgb(255,247,226);val green=Color.rgb(18,70,57);val coral=Color.rgb(210,69,48)
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(cream);p.color=green;c.drawRect(0f,0f,1080f,240f,p)
        fun text(value:String,size:Float,left:Float,top:Float,width:Int,color:Int,bold:Boolean,max:Int,align:Layout.Alignment=Layout.Alignment.ALIGN_NORMAL) {
            val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{textSize=size;this.color=color;typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT}
            val layout=StaticLayout.Builder.obtain(value,0,value.length,paint,width).setAlignment(align).setIncludePad(false).setMaxLines(max).setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(7f,1f).build()
            c.save();c.translate(left,top);layout.draw(c);c.restore()
        }
        text(title.uppercase(java.util.Locale.forLanguageTag("es-PE")),64f,66f,51f,948,cream,true,2)
        p.color=coral;c.drawCircle(276f,441f,161f,p)
        if(emblem=="cat") {
            p.color=cream
            val ears=Path().apply{moveTo(158f,407f);lineTo(160f,301f);lineTo(240f,352f);lineTo(315f,352f);lineTo(394f,301f);lineTo(395f,411f);close()}
            c.drawPath(ears,p);c.drawOval(156f,345f,396f,543f,p)
            p.color=green;c.drawOval(215f,410f,233f,437f,p);c.drawOval(319f,410f,337f,437f,p)
            val nose=Path().apply{moveTo(264f,450f);lineTo(288f,450f);lineTo(276f,464f);close()};c.drawPath(nose,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=5f;p.strokeCap=Paint.Cap.ROUND
            c.drawLine(276f,463f,276f,479f,p);c.drawArc(250f,464f,277f,492f,0f,160f,false,p);c.drawArc(276f,464f,303f,492f,20f,160f,false,p)
            for(i in 0..1){c.drawLine(195f,466f+i*14,136f,455f+i*30,p);c.drawLine(357f,466f+i*14,416f,455f+i*30,p)}
            p.style=Paint.Style.FILL
        }
        text("MARCA EL",42f,600f,284f,360,green,true,1,Layout.Alignment.ALIGN_CENTER)
        p.color=green;p.style=Paint.Style.STROKE;p.strokeWidth=9f;c.drawRoundRect(626f,346f,958f,581f,24f,24f,p);p.style=Paint.Style.FILL
        text(number,if(number.length>2)160f else 202f,638f,350f,308,green,true,1,Layout.Alignment.ALIGN_CENTER)
        p.color=coral;c.drawRoundRect(66f,642f,1014f,651f,4f,4f,p)
        text(slogan,58f,66f,693f,948,green,true,2)
        text(description,32f,66f,859f,948,green,false,3)
        text(footer,24f,66f,997f,948,green,true,2)
        return bitmap
    }
}
