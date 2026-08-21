package com.cb

object UprotSession {

    @Volatile
    var cookieHeader: String = ""

    @Volatile
    var userAgent: String = ""

    fun clear() {
        cookieHeader = ""
        userAgent = ""
    }
}
