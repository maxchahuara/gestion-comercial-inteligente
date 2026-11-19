package org.campana.agent.phone

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.roundToInt
import org.campana.agent.R

/** Main-thread, service-owned windows. Only the small recovery card receives touches. */
internal class ControlOverlay(private val service:AccessibilityService,private val onRecover:()->Unit,private val onOutside:(Long)->Unit={onRecover()}) {
    private val manager=service.getSystemService(WindowManager::class.java)
    private var halo:View?=null
    private var card:LinearLayout?=null
    private var cardParams:WindowManager.LayoutParams?=null
    private var bottom=false
    private var insets:WindowInsets?=null
    val showing:Boolean get()=halo?.isAttachedToWindow==true && card?.isAttachedToWindow==true && (card?.width ?: 0)>0 && (card?.height ?: 0)>0
    private fun dp(value:Int)=(value*service.resources.displayMetrics.density+.5f).toInt()
    private fun params(width:Int,height:Int,untouchable:Boolean)=WindowManager.LayoutParams(
        width,height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            (if(untouchable)WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity=Gravity.TOP or Gravity.END
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    fun show() {
        check(halo==null && card==null)
        try {
            val border=object:View(service) {
                private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply{style=Paint.Style.STROKE;color=0xFF368DFF.toInt()}
                private val bounds=RectF()
                private val clip=Path()
                private val strokeWidths=FloatArray(73)
                private val strokeAlphas=IntArray(73)
                init {
                    // Composite thin strokes into one continuous inward fade, not opaque rings.
                    var previous=0f
                    for(index in strokeWidths.indices) {
                        val t=index/72f;val opacity=t*t*t
                        strokeWidths[index]=(2*dp(48)*(1-t)).coerceAtLeast(2f)
                        strokeAlphas[index]=if(previous>=1f)255 else ((opacity-previous)/(1-previous)*255).roundToInt().coerceIn(0,255)
                        previous=opacity
                    }
                }
                override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
                    bounds.set(0f,0f,w.toFloat(),h.toFloat());clip.reset()
                    clip.addRoundRect(bounds,dp(27).toFloat(),dp(27).toFloat(),Path.Direction.CW)
                }
                override fun onDraw(canvas:Canvas) {
                    val save=canvas.save();canvas.clipPath(clip)
                    for(index in strokeWidths.indices) {
                        paint.strokeWidth=strokeWidths[index];paint.alpha=strokeAlphas[index]
                        canvas.drawRoundRect(bounds,dp(27).toFloat(),dp(27).toFloat(),paint)
                    }
                    canvas.restoreToCount(save)
                }
            }.apply { id=R.id.control_halo;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            halo=border
            manager.addView(border,params(-1,-1,true).apply{title="${service.getString(R.string.app_name)} · Halo de control"})
            val panel=LinearLayout(service).apply {
                setOnTouchListener{_,event->if(event.actionMasked==MotionEvent.ACTION_OUTSIDE){onOutside(event.eventTime);true}else false}
                orientation=LinearLayout.VERTICAL;setPadding(dp(2),dp(2),dp(2),dp(2));elevation=dp(5).toFloat()
                background=GradientDrawable().apply {setColor(0xFF0B2342.toInt());cornerRadius=dp(26).toFloat();setStroke(dp(1),0xFF65ADFF.toInt())}
                addView(Button(service,null,0).apply {
                    val status=service.getString(R.string.control_status);val action=service.getString(R.string.control_recover)
                    val caption=SpannableString(status+"\n"+action).apply {
                        setSpan(RelativeSizeSpan(11.5f/14f),0,status.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(ForegroundColorSpan(0xFFBEDAFF.toInt()),0,status.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(StyleSpan(Typeface.BOLD),status.length+1,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    id=R.id.control_recover;text=caption;isAllCaps=false;textSize=14f;gravity=Gravity.CENTER;maxLines=4;includeFontPadding=false
                    setTextColor(Color.WHITE);minHeight=dp(48);minimumHeight=dp(48);setPadding(dp(8),dp(4),dp(8),dp(4))
                    background=RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),GradientDrawable().apply{setColor(0xFF0B2342.toInt());cornerRadius=dp(24).toFloat()},null)
                    setOnClickListener{onRecover()}
                    setOnTouchListener{_,event->if(event.actionMasked==MotionEvent.ACTION_DOWN)onRecover();false}
                },LinearLayout.LayoutParams(-1,-2))
            }
            card=panel
            val p=params(cardWidth(),-2,false).apply {title="${service.getString(R.string.app_name)} · Recuperar control"}
            cardParams=p;place(p,dp(52))
            manager.addView(panel,p)
            border.setOnApplyWindowInsetsListener { _,value->insets=value;relayout();value }
            panel.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_->relayout() }
            border.requestApplyInsets()
        } catch(e:Exception) {hide();throw e}
    }
    private fun cardWidth():Int=(manager.currentWindowMetrics.bounds.width()-dp(32)).coerceAtMost(dp(184)).coerceAtLeast(dp(160))
    private fun place(p:WindowManager.LayoutParams,height:Int) {
        val metrics=manager.currentWindowMetrics
        val safe=(insets ?: metrics.windowInsets).getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        p.x=safe.right+dp(12)
        val first=safe.top+dp(8)
        val last=(metrics.bounds.height()-safe.bottom-height-dp(16)).coerceAtLeast(safe.top+dp(8))
        p.y=if(bottom)last else first.coerceAtMost(last)
    }
    private fun relayout() {
        val panel=card ?: return;val p=cardParams ?: return
        val oldX=p.x;val oldY=p.y;val oldWidth=p.width
        p.width=cardWidth();place(p,panel.height.takeIf{it>0} ?: dp(52))
        if(panel.isAttachedToWindow && (oldX!=p.x || oldY!=p.y || oldWidth!=p.width))manager.updateViewLayout(panel,p)
    }
    fun covers(rect:Rect):Boolean {
        val panel=card ?: return false
        val location=IntArray(2);panel.getLocationOnScreen(location)
        return Rect(location[0],location[1],location[0]+panel.width,location[1]+panel.height).contains(rect.centerX(),rect.centerY())
    }
    /** Move the card away before a tool acts; the caller waits for the window traversal. */
    fun avoid(rect:Rect):Boolean {
        if(!covers(rect))return false
        bottom=!bottom;relayout();return true
    }
    fun hide() {
        card?.let{runCatching{manager.removeViewImmediate(it)}}
        halo?.let{runCatching{manager.removeViewImmediate(it)}}
        card=null;halo=null;cardParams=null;insets=null;bottom=false
    }
}
