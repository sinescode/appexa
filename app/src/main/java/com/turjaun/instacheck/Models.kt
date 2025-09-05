package com.turjaun.instacheck

enum class UsernameStatus { ACTIVE, AVAILABLE, ERROR, CANCELLED }

data class UsernameCheck(
    val username: String,
    val status: UsernameStatus,
    val message: String? = null
)

data class SessionResult(
    val results: MutableList<UsernameCheck> = mutableListOf(),
    var completed: Boolean = false,
    val stats: Stats = Stats()
)

data class Stats(
    var activeCount: Int = 0,
    var availableCount: Int = 0,
    var errorCount: Int = 0,
    var cancelledCount: Int = 0,
    var totalCount: Int = 0
)
