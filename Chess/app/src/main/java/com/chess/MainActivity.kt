package com.chess

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.chess.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val authToken = BuildConfig.API_KEY_BOT
    private lateinit var currentGameId: String
    private val chessBoardGrid: GridLayout by lazy { findViewById(R.id.chessBoardGrid) }
    private val turnTextView: TextView by lazy { findViewById(R.id.turnTextView) }
    private val playerColorTextView: TextView by lazy { findViewById(R.id.playerColor) }
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val gameStarted = false

    private var selectedPiece: Pair<Int, Int>? = null
    private var boardState: Array<Array<Char?>> = Array(8) { Array(8) { null } }
    private var currentTurn: Char = 'w'
    private var playerColor: Char = 'w'
    private var opponentFirst = false
    private var myFirstMove = false

    private val updateFlow = MutableSharedFlow<Array<Array<Char?>>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d("MainActivity", "Token: $authToken")
        val displayMetrics = resources.displayMetrics
        val cellSize = displayMetrics.widthPixels / 8

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val cellFrame = FrameLayout(this)
                val isWhite = (row + col) % 2 == 0
                cellFrame.setBackgroundColor(if (isWhite) android.graphics.Color.WHITE else android.graphics.Color.BLACK)

                val params = GridLayout.LayoutParams().apply {
                    width = cellSize
                    height = cellSize
                }
                chessBoardGrid.addView(cellFrame, params)

                val piece = boardState[row][col]
                if (piece != null) {
                    val imageView = ImageView(this)
                    imageView.setImageResource(getPieceResource(piece))
                    imageView.layoutParams = FrameLayout.LayoutParams(cellSize, cellSize)
                    cellFrame.addView(imageView)

                    imageView.setOnClickListener {
                        if (currentTurn == playerColor) {
                            if (piece.isUpperCase() == (playerColor == 'w')) {
                                selectedPiece = Pair(row, col)
                            } else {
                                cellFrame.performClick()
                            }
                        }
                    }
                }

                cellFrame.setOnClickListener {
                    val targetPiece = boardState[row][col]

                    if (!gameStarted) {
                        Toast.makeText(
                            this@MainActivity, "Игра не начата", Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    if (selectedPiece != null) {
                        val (startRow, startCol) = selectedPiece!!
                        val isAttack =
                            targetPiece != null && targetPiece.isUpperCase() != (playerColor == 'w')

                        if (targetPiece == null || isAttack) {
                            makeMove(
                                "${getChessNotation(startCol)}${8 - startRow}${getChessNotation(col)}${8 - row}"
                            )
                            selectedPiece = null
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Невозможно выполнить ход на выбранную клетку",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        if (targetPiece == null || targetPiece.isUpperCase() != (playerColor == 'w')) {
                            Toast.makeText(
                                this@MainActivity, "Это не ваша фигура!", Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            selectedPiece = Pair(row, col)
                        }
                    }
                }
            }
        }

        binding.startButton.setOnClickListener {
            startGameAgainstBot()
        }

        lifecycleScope.launch {
            updateFlow.collect { boardState ->
                updateChessBoard(boardState)
            }
        }
    }

    private fun startGameAgainstBot() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val instance = LichessApiService.RetrofitInstance.create()
                    instance.startGameAgainstBot(
                        "Bearer $authToken", StartGameRequest(level = 1)
                    )
                }

                currentGameId = response.id
                Log.d("MainActivity", "Game started: $response")

                if (response.status.name == "started") {
                    binding.startButton.visibility = View.GONE
                    val (newBoardState, turn) = parseFen(response.fen)
                    boardState = newBoardState
                    currentTurn = turn
                    updateChessBoard(newBoardState)

                    playerColorTextView.text = "Определение вашего цвета..."

                    streamBotGameState(currentGameId)

                    lifecycleScope.launch {
                        delay(3000)
                        if (currentTurn != 'w') {
                            opponentFirst = true
                            playerColor = 'b'
                            playerColorTextView.text = "Ваш цвет: Черные"
                        } else {
                            opponentFirst = false
                            playerColor = 'w'
                            playerColorTextView.text = "Ваш цвет: Белые"
                        }
                    }
                }
                Toast.makeText(this@MainActivity, "Игра началась", Toast.LENGTH_SHORT).show()

            } catch (e: HttpException) {
                Log.d("MainActivity", "Error starting game: ${e.response()?.errorBody()?.string()}")
                Toast.makeText(this@MainActivity, "Ошибка при старте игры", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun streamBotGameState(gameId: String) {
        val request = Request.Builder().url("https://lichess.org/api/bot/game/stream/$gameId")
            .addHeader("Authorization", "Bearer $authToken").build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e("MainActivity", "Streaming error: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e("MainActivity", "Streaming response error: ${response.message}")
                    return
                }

                val responseBody = response.body
                if (responseBody != null) {
                    val source = responseBody.source()
                    val buffer = Buffer()
                    while (source.read(buffer, 8192) != -1L) {
                        val line = buffer.readUtf8Line()
                        if (line != null) {
                            handleGameStateUpdate(line)
                        }
                    }
                }
            }
        })
    }

    private fun handleGameStateUpdate(message: String) {
        if (message.isNotEmpty()) {
            try {
                Log.d("MainActivity", "Game state update: $message")
                val jsonObject = JSONObject(message)
                if (jsonObject.getString("type") == "gameState") {
                    val moves = jsonObject.getString("moves").split(" ")
                    val lastMove = moves.lastOrNull() ?: return
                    if (myFirstMove) {
                        opponentFirst = true
                    }

                    val status = jsonObject.optString("status", "")

                    if (status.isNotEmpty() && status != "started") {
                        handleGameEnd(status)
                        return
                    }

                    val (startCol, startRow, endCol, endRow) = parseMove(lastMove)

                    if (boardState[startRow][startCol] != null) {
                        boardState[endRow][endCol] = boardState[startRow][startCol]
                        boardState[startRow][startCol] = null

                        currentTurn = if (currentTurn == 'w') 'b' else 'w'

                        CoroutineScope(Dispatchers.Main).launch {
                            updateFlow.emit(boardState)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка обработки gameState: ${e.message}")
            }
        }
    }

    private fun handleGameEnd(status: String) {
        val endMessage = when (status) {
            "mate" -> "Мат! Игра завершена."
            "resign" -> "Соперник сдался. Победа!"
            "stalemate" -> "Победа, ему некуда бежать."
            "draw" -> "Игра завершена вничью."
            "timeOut" -> "Время истекло. Победа!"
            "aborted" -> "Игра была прервана."
            else -> "Игра завершена."
        }

        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(this@MainActivity, endMessage, Toast.LENGTH_LONG).show()
            turnTextView.text = "Игра завершена"
            binding.startButton.visibility = View.VISIBLE
        }

        Log.d("MainActivity", "Game ended with status: $status")
    }

    private fun makeMove(move: String) {
        currentGameId.let { gameId ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val instance = LichessApiService.RetrofitInstance.create()
                    Log.d("MainActivity", "Making move: $move")
                    val response = instance.makeMove(
                        "Bearer $authToken", gameId, move
                    )
                    myFirstMove = true
                    Log.d("MainActivity", "Move response: $response")

                    withContext(Dispatchers.Main) {
                        if (response.ok) {
                            val (startCol, startRow, endCol, endRow) = parseMove(move)
                            val movingPiece = boardState[startRow][startCol]

                            if (movingPiece != null) {
                                boardState[endRow][endCol] = movingPiece
                                boardState[startRow][startCol] = null

                                currentTurn = if (currentTurn == 'w') 'b' else 'w'

                                updateFlow.emit(boardState)
                            }
                        } else {
                            Toast.makeText(
                                this@MainActivity, "Ошибка при выполнении хода", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity, "Ход невозможен", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun updateChessBoard(boardState: Array<Array<Char?>>) {
        chessBoardGrid.removeAllViews()
        val displayMetrics = resources.displayMetrics
        val cellSize = displayMetrics.widthPixels / 8

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val cellFrame = FrameLayout(this)
                val isWhite = (row + col) % 2 == 0
                cellFrame.setBackgroundColor(if (isWhite) android.graphics.Color.WHITE else android.graphics.Color.BLACK)

                val params = GridLayout.LayoutParams().apply {
                    width = cellSize
                    height = cellSize
                }
                chessBoardGrid.addView(cellFrame, params)

                val piece = boardState[row][col]
                if (piece != null) {
                    val imageView = ImageView(this)
                    imageView.setImageResource(getPieceResource(piece))
                    imageView.layoutParams = FrameLayout.LayoutParams(cellSize, cellSize)
                    cellFrame.addView(imageView)

                    imageView.setOnClickListener {
                        if (currentTurn == playerColor) {
                            if (piece.isUpperCase() == (playerColor == 'w')) {
                                selectedPiece = Pair(row, col)
                            } else {
                                cellFrame.performClick()
                            }
                        }
                    }
                }

                cellFrame.setOnClickListener {
                    val targetPiece = boardState[row][col]

                    if (selectedPiece != null) {
                        val (startRow, startCol) = selectedPiece!!
                        val isAttack =
                            targetPiece != null && targetPiece.isUpperCase() != (playerColor == 'w')

                        if (targetPiece == null || isAttack) {
                            makeMove(
                                "${getChessNotation(startCol)}${8 - startRow}${getChessNotation(col)}${8 - row}"
                            )
                            selectedPiece = null
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Невозможно выполнить ход на выбранную клетку",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        if (targetPiece == null || targetPiece.isUpperCase() != (playerColor == 'w')) {
                            Toast.makeText(
                                this@MainActivity, "Это не ваша фигура!", Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            selectedPiece = Pair(row, col)
                        }
                    }
                }
            }
        }

        turnTextView.text = "Ход: ${if (currentTurn == 'w') "Белые" else "Черные"}"
    }

    private fun parseFen(fen: String): Pair<Array<Array<Char?>>, Char> {
        val boardState = Array(8) { Array<Char?>(8) { null } }
        var row = 0
        var col = 0
        var i = 0
        var turn = 'w'

        while (i < fen.length && fen[i] != ' ') {
            when (val c = fen[i]) {
                in '1'..'8' -> col += c.toString().toInt()
                in 'a'..'z', in 'A'..'Z' -> boardState[row][col++] = c
                '/' -> {
                    row++
                    col = 0
                }
            }
            i++
        }

        while (i < fen.length && fen[i] != ' ') {
            turn = fen[i]
            i++
        }

        return Pair(boardState, turn)
    }

    private fun getPieceResource(piece: Char): Int {
        return when (piece) {
            'p' -> R.drawable.black_pawn
            'r' -> R.drawable.black_rook
            'n' -> R.drawable.black_knight
            'b' -> R.drawable.black_bishop
            'q' -> R.drawable.black_queen
            'k' -> R.drawable.black_king
            'P' -> R.drawable.white_pawn
            'R' -> R.drawable.white_rook
            'N' -> R.drawable.white_knight
            'B' -> R.drawable.white_bishop
            'Q' -> R.drawable.white_queen
            'K' -> R.drawable.white_king
            else -> throw IllegalArgumentException("Unknown piece: $piece")
        }
    }

    private fun getChessNotation(col: Int): Char {
        return 'a' + col
    }

    private fun parseMove(move: String): MoveData {
        val startCol = move[0] - 'a'
        val startRow = 8 - move[1].toString().toInt()
        val endCol = move[2] - 'a'
        val endRow = 8 - move[3].toString().toInt()
        return MoveData(startCol, startRow, endCol, endRow)
    }

    suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }
}