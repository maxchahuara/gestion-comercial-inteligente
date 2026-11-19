package org.campana.agent.agent

/** Taking back the phone cannot undo a submission or turn it into a retryable task. */
internal fun interruptedTaskStatus(current:String?,networkFailure:Boolean=false):String=when(current) {
    "SUBMITTING"->"UNCERTAIN"
    "SUBMITTED","UNCERTAIN","CANCELLED"->current
    else->if(networkFailure)"WAITING_NETWORK" else "WAITING_OWNER"
}
