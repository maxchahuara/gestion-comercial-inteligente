package org.campana.agent

import android.app.Notification
import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.campana.agent.data.*
import org.campana.agent.phone.IncomingNotifications
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native notification fixtures only: nothing is posted and no PendingIntent is sent. */
@RunWith(AndroidJUnit4::class)
class IncomingReplyTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val now=System.currentTimeMillis()
    private val owner=Person.Builder().setName("Propietario QA").setKey("owner").build()
    private val friend=Person.Builder().setName("Amigo QA").setKey("friend").build()
    private fun notification(sender:Person?=friend,group:Boolean=false,title:String="Amigo QA",text:String="Hola",time:Long=now,pkg:String="com.whatsapp",plain:Boolean=false,category:String=Notification.CATEGORY_MESSAGE,summary:Boolean=false):StatusBarNotification {
        val builder=NotificationCompat.Builder(context,"agent").setSmallIcon(org.campana.agent.R.drawable.ic_launcher)
            .setContentTitle(title).setContentText(text).setCategory(category)
        if(!plain)builder.setStyle(NotificationCompat.MessagingStyle(owner).setGroupConversation(group)
            .addMessage(NotificationCompat.MessagingStyle.Message(text,time,sender)))
        val n=builder.build();n.extras.putCharSequence(Notification.EXTRA_TITLE,title)
        if(summary)n.flags=n.flags or Notification.FLAG_GROUP_SUMMARY
        return StatusBarNotification(pkg,pkg,7,null,Process.myUid(),Process.myPid(),0,n,UserHandle.getUserHandleForUid(Process.myUid()),now)
    }
    @Test fun nativeDirectMessageHasStableScopedFingerprint() {
        val a=IncomingNotifications.parse(notification(),now)!!
        assertTrue(a.direct);assertEquals("Amigo QA",a.title);assertEquals("Hola",a.text)
        assertEquals(a.fingerprint,IncomingNotifications.parse(notification(),now)!!.fingerprint)
        assertNotEquals(a.fingerprint,IncomingNotifications.parse(notification(text="Otra consulta"),now)!!.fingerprint)
        assertNotEquals(a.fingerprint,IncomingNotifications.parse(notification(pkg="com.instagram.android"),now)!!.fingerprint)
    }
    @Test fun groupsAndSummariesNeverEnterTheReplyQueue() {
        assertNull(IncomingNotifications.parse(notification(group=true),now))
        assertNull(IncomingNotifications.parse(notification(summary=true),now))
        assertNull(IncomingNotifications.parse(notification(title="Grupo QA"),now))
    }
    @Test fun ownerRepliesCannotTriggerASelfReplyLoop() {
        assertNull(IncomingNotifications.parse(notification(sender=null),now))
        assertNull(IncomingNotifications.parse(notification(sender=owner,title="Propietario QA"),now))
    }
    @Test fun aCommentOrPlainAlertIsNotADirectMessage() {
        assertNull(IncomingNotifications.parse(notification(plain=true),now))
        assertNull(IncomingNotifications.parse(notification(category=Notification.CATEGORY_SOCIAL),now))
        assertNull(IncomingNotifications.parse(notification(pkg=context.packageName),now))
    }
    @Test fun staleFutureAndEmptyMessagesAreRejected() {
        assertNull(IncomingNotifications.parse(notification(time=now-org.campana.agent.phone.IncomingNotificationRules.MAX_PENDING_AGE_MILLIS-1),now))
        assertNull(IncomingNotifications.parse(notification(time=now+1),now))
        assertNull(IncomingNotifications.parse(notification(text=" "),now))
    }
    @Test fun aWhatsAppDirectMessageCanOmitTheOptionalCategory() {
        val native=notification()
        native.notification.category=null
        assertNotNull(IncomingNotifications.parse(native,now))
        assertNull(IncomingNotifications.parse(notification(plain=true).also{it.notification.category=null},now))
        assertNull(IncomingNotifications.parse(notification(group=true).also{it.notification.category=null},now))
    }
    @Test fun messengerRequiresNativeDirectMessagingEvidence() {
        val native=notification(pkg="com.facebook.orca")
        assertEquals(Channel.MESSENGER,IncomingNotifications.parse(native,now)!!.channel)
        native.notification.category=null
        assertNotNull(IncomingNotifications.parse(native,now))
        assertNull(IncomingNotifications.parse(notification(pkg="com.facebook.orca",group=true),now))
        assertNull(IncomingNotifications.parse(notification(pkg="com.facebook.orca",plain=true).also{it.notification.category=null},now))
    }
    @Test fun processRecoveryRetriesOnlyRepliesWithNoSubmissionAttempt()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,CampaignDatabase::class.java).build()
        try {
            val dao=db.dao();val c=Campaign(name="Recuperación QA");dao.save(c)
            val unsent=AgentTask(campaignId=c.id,channel="WHATSAPP",kind="REPLY",instruction="Hola",status="RUNNING")
            val attempted=unsent.copy(id=newId());val submitting=unsent.copy(id=newId(),status="SUBMITTING")
            val post=unsent.copy(id=newId(),kind="POST")
            listOf(unsent,attempted,submitting,post).forEach{dao.insert(it)}
            dao.insert(Receipt(campaignId=c.id,taskId=attempted.id,action="external_submit",status="SUBMITTING",detail="Intento local de prueba"))
            dao.recoverInterrupted()
            assertEquals("QUEUED",dao.task(unsent.id)!!.status)
            listOf(attempted,submitting,post).forEach{assertEquals("UNCERTAIN",dao.task(it.id)!!.status)}
        } finally {db.close()}
    }
    @Test fun replyMemoryNeverCrossesContactsChannelsCampaignsOrUnsubmittedTasks()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,CampaignDatabase::class.java).build()
        try {
            val dao=db.dao();val c=Campaign(name="Memoria QA");dao.save(c)
            val base=AgentTask(campaignId=c.id,channel="WHATSAPP",kind="REPLY",instruction="Hola",recipient="Amigo QA",notificationKey="native-thread-a",status="SUBMITTED",detail="Presentada\n\nHola, soy el asistente.")
            repeat(4){dao.insert(base.copy(id=newId(),createdAt=it.toLong()))}
            dao.insert(base.copy(id=newId(),recipient="Otra persona",notificationKey="native-thread-c",createdAt=9))
            dao.insert(base.copy(id=newId(),notificationKey="native-thread-b",createdAt=9))
            dao.insert(base.copy(id=newId(),channel="INSTAGRAM",createdAt=9))
            dao.insert(base.copy(id=newId(),campaignId=newId(),createdAt=9))
            dao.insert(base.copy(id=newId(),status="UNCERTAIN",createdAt=9))
            val history=dao.replyHistoryForConversation(c.id,"WHATSAPP","native-thread-a")
            assertEquals(3,history.size);assertEquals(listOf(3L,2L,1L),history.map{it.createdAt})
        } finally {db.close()}
    }
}
