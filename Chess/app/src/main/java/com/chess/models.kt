package com.chess

data class StartGameRequest(val level: Int)
data class LichessGameResponse(
    val id: String,
    val variant: Variant,
    val speed: String,
    val perf: String,
    val rated: Boolean,
    val fen: String,
    val turns: Int,
    val source: String,
    val status: Status,
    val createdAt: Long,
    val player: String,
)

data class Variant(
    val key: String,
    val name: String,
    val short: String,
)

data class Status(
    val id: Int,
    val name: String,
)

data class LichessMoveResponse(val ok: Boolean)

data class MoveData(val startCol: Int, val startRow: Int, val endCol: Int, val endRow: Int)