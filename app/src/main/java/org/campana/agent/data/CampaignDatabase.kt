package org.campana.agent.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CampaignDao {
    @Query("SELECT * FROM Campaign ORDER BY updatedAt DESC") fun watchCampaigns(): Flow<List<Campaign>>
    @Query("SELECT * FROM Campaign ORDER BY updatedAt DESC") suspend fun campaigns(): List<Campaign>
    @Query("SELECT * FROM Campaign WHERE id=:id") suspend fun campaign(id: String): Campaign?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(campaign: Campaign)
    @Query("DELETE FROM Campaign WHERE id=:id") suspend fun deleteCampaignRow(id: String)
    @Query("SELECT * FROM AgentTask ORDER BY dueAt DESC") fun watchTasks(): Flow<List<AgentTask>>
    @Query("SELECT * FROM AgentTask WHERE id=:id") suspend fun task(id: String): AgentTask?
    @Query("SELECT * FROM AgentTask WHERE dedupeKey=:key LIMIT 1") suspend fun taskForDedupe(key:String):AgentTask?
    @Query("SELECT * FROM AgentTask WHERE campaignId=:id ORDER BY dueAt DESC") suspend fun tasks(id: String): List<AgentTask>
    @Query("SELECT * FROM AgentTask WHERE campaignId=:id AND channel=:channel AND recipient=:recipient AND kind='REPLY' AND status='SUBMITTED' ORDER BY createdAt DESC LIMIT 3")
    suspend fun replyHistory(id:String,channel:String,recipient:String):List<AgentTask>
    @Query("SELECT * FROM AgentTask WHERE campaignId=:id AND channel=:channel AND notificationKey=:conversation AND notificationKey!='' AND kind='REPLY' AND status='SUBMITTED' ORDER BY createdAt DESC LIMIT 3")
    suspend fun replyHistoryForConversation(id:String,channel:String,conversation:String):List<AgentTask>
    @Query("SELECT * FROM AgentTask WHERE status IN ('QUEUED','WAITING_NETWORK','WAITING_UNLOCK','WAITING_PERMISSION') AND dueAt<=:now ORDER BY dueAt LIMIT 1")
    suspend fun nextTask(now: Long): AgentTask?
    @Query("SELECT * FROM AgentTask WHERE status IN ('QUEUED','WAITING_NETWORK','WAITING_UNLOCK','WAITING_PERMISSION') AND dueAt<=:now ORDER BY dueAt LIMIT 5")
    suspend fun dueTasks(now:Long):List<AgentTask>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(task: AgentTask): Long
    @Update suspend fun update(task: AgentTask)
    @Query("UPDATE AgentTask SET status='RUNNING', updatedAt=:now WHERE id=:id AND status IN ('QUEUED','WAITING_NETWORK','WAITING_UNLOCK','WAITING_PERMISSION')")
    suspend fun claim(id: String, now: Long): Int
    @Query("UPDATE AgentTask SET status='QUEUED', detail='Revisada por el propietario.', updatedAt=:now WHERE id=:id AND status IN ('DRAFT','WAITING_OWNER','WAITING_NETWORK','WAITING_UNLOCK','WAITING_PERMISSION')")
    suspend fun queueReviewed(id:String,now:Long):Int
    @Query("UPDATE AgentTask SET status='WAITING_OWNER', detail=:detail, updatedAt=:now WHERE id=:id AND status IN ('QUEUED','WAITING_NETWORK','WAITING_UNLOCK','WAITING_PERMISSION')")
    suspend fun waitBeforeStarting(id:String,detail:String,now:Long):Int
    @Query("UPDATE AgentTask SET status='CANCELLED', detail='Intentos pendientes cancelados por el propietario.', updatedAt=:now WHERE id=:id AND status IN ('DRAFT','QUEUED','WAITING_OWNER','WAITING_NETWORK','WAITING_UNLOCK','WAITING_PERMISSION')")
    suspend fun cancelPending(id:String,now:Long):Int
    @Query("UPDATE AgentTask SET status=:status, detail=:detail, updatedAt=:now WHERE id=:id")
    suspend fun taskState(id: String, status: String, detail: String, now: Long = System.currentTimeMillis())
    @Query("UPDATE AgentTask SET status=CASE WHEN kind='REPLY' AND status='RUNNING' AND NOT EXISTS (SELECT 1 FROM Receipt WHERE Receipt.taskId=AgentTask.id AND Receipt.action='external_submit') THEN 'QUEUED' ELSE 'UNCERTAIN' END, detail='Sesión interrumpida: se retoma solo una respuesta sin intento de envío; los efectos inciertos requieren revisión.' WHERE status IN ('RUNNING','SUBMITTING')")
    suspend fun recoverInterrupted()
    @Query("SELECT * FROM ChatMessage ORDER BY createdAt") fun watchMessages(): Flow<List<ChatMessage>>
    @Query("SELECT * FROM ChatMessage WHERE campaignId=:id ORDER BY createdAt DESC LIMIT 18") suspend fun history(id: String): List<ChatMessage>
    @Query("SELECT * FROM ChatMessage WHERE campaignId=:id ORDER BY createdAt") suspend fun allMessages(id:String):List<ChatMessage>
    @Insert suspend fun insert(message: ChatMessage)
    @Query("SELECT * FROM ChatMessage WHERE id=:id") suspend fun message(id:String):ChatMessage?
    @Query("UPDATE ChatMessage SET role='notice', text=:text WHERE id=:id AND role='rules_proposal'")
    suspend fun resolveRuleProposal(id:String,text:String):Int
    @Query("UPDATE ChatMessage SET role='notice', text=:text WHERE campaignId=:id AND role='rules_proposal'")
    suspend fun resolveRuleProposals(id:String,text:String):Int
    @Query("SELECT * FROM Asset ORDER BY createdAt DESC") fun watchAssets(): Flow<List<Asset>>
    @Query("SELECT * FROM Asset WHERE campaignId=:id") suspend fun assets(id: String): List<Asset>
    @Query("SELECT * FROM Asset WHERE id=:id") suspend fun asset(id: String): Asset?
    @Insert suspend fun insert(asset: Asset)
    @Query("SELECT * FROM Lead ORDER BY name") fun watchLeads(): Flow<List<Lead>>
    @Query("SELECT * FROM Lead WHERE campaignId=:id") suspend fun leads(id: String): List<Lead>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(lead: Lead)
    @Query("SELECT * FROM Receipt ORDER BY createdAt DESC LIMIT 200") fun watchReceipts(): Flow<List<Receipt>>
    @Insert suspend fun insert(receipt: Receipt)
    @Query("SELECT * FROM Receipt WHERE campaignId=:id ORDER BY createdAt") suspend fun allReceipts(id:String):List<Receipt>
    @Query("SELECT * FROM Receipt WHERE taskId=:id ORDER BY createdAt") suspend fun receiptsForTask(id:String):List<Receipt>
    @Query("SELECT * FROM Receipt WHERE taskId=:id AND action=:action ORDER BY createdAt DESC LIMIT 1") suspend fun latestReceiptForTask(id:String,action:String):Receipt?
    @Query("SELECT EXISTS(SELECT 1 FROM Receipt WHERE taskId=:id AND action=:action)") suspend fun hasReceipt(id:String,action:String):Boolean
    @Query("SELECT * FROM Receipt WHERE campaignId=:id AND action=:action ORDER BY createdAt DESC LIMIT 64") suspend fun recentReceipts(id:String,action:String):List<Receipt>
    @Query("SELECT * FROM Receipt WHERE campaignId=:id AND action=:action AND detail=:detail ORDER BY createdAt DESC, rowid DESC LIMIT 1")
    suspend fun latestGuideReceipt(id: String, action: String, detail: String): Receipt?
    @Query("UPDATE AgentTask SET status='QUEUED', dueAt=:now, updatedAt=:now, detail='Revisando mensajes pendientes de la fanpage.' WHERE id=:id AND kind='READ_INBOX' AND status IN ('COMPLETED','WAITING_OWNER','WAITING_NETWORK','WAITING_UNLOCK','WAITING_PERMISSION','UNCERTAIN')")
    suspend fun queueInboxScan(id:String,now:Long):Int
    @Query("SELECT COUNT(*) FROM Receipt WHERE campaignId=:id AND action='external_submit' AND createdAt>=:since")
    suspend fun submissions(id: String, since: Long): Int
    @Query("DELETE FROM AgentTask WHERE campaignId=:id") suspend fun deleteTasks(id: String)
    @Query("DELETE FROM ChatMessage WHERE campaignId=:id") suspend fun deleteMessages(id: String)
    @Query("DELETE FROM Asset WHERE campaignId=:id") suspend fun deleteAssets(id: String)
    @Query("DELETE FROM Lead WHERE campaignId=:id") suspend fun deleteLeads(id: String)
    @Query("DELETE FROM Receipt WHERE campaignId=:id") suspend fun deleteReceipts(id: String)
    @Transaction suspend fun deleteCampaign(id: String) {
        deleteTasks(id); deleteMessages(id); deleteAssets(id); deleteLeads(id); deleteReceipts(id); deleteCampaignRow(id)
    }
}

@Database(entities = [Campaign::class, AgentTask::class, ChatMessage::class, Asset::class, Lead::class, Receipt::class],
    version = 1, exportSchema = true)
abstract class CampaignDatabase : RoomDatabase() { abstract fun dao(): CampaignDao }
