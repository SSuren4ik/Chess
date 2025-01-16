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
import com.chess.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val authToken = BuildConfig.API_KEY_BOT
    private lateinit var currentGameId: String
    private val chessBoardGrid: GridLayout by lazy { findViewById(R.id.chessBoardGrid) }
    private val turnTextView: TextView by lazy { findViewById(R.id.turnTextView) }
    private val playerColorTextView: TextView by lazy { findViewById(R.id.playerColor) }
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Устанавливаем бесконечное ожидание данных
        .build()

    private var selectedPiece: Pair<Int, Int>? = null // Хранит координаты выбранной фигуры
    private var boardState: Array<Array<Char?>> = Array(8) { Array(8) { null } }
    private var currentTurn: Char = 'w'
    private var playerColor: Char = 'w'

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
                        if (currentTurn == playerColor && (piece.isUpperCase() == (playerColor == 'w'))) {
                            selectedPiece = Pair(row, col)
                        }
                    }
                }

                cellFrame.setOnClickListener {
                    selectedPiece?.let { (startRow, startCol) ->
                        if (boardState[row][col] == null && boardState[startRow][startCol]?.isUpperCase() == (playerColor == 'w')) {
                            makeMove(
                                "${getChessNotation(startCol)}${8 - startRow}${
                                    getChessNotation(
                                        col
                                    )
                                }${8 - row}"
                            )
                            selectedPiece = null
                        }
                    }
                }
            }
        }

        binding.startButton.setOnClickListener {
            startGameAgainstBot()
        }
    }

    private fun startGameAgainstBot() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val instance = LichessApiService.RetrofitInstance.create()
                    instance.startGameAgainstBot("Bearer $authToken", StartGameRequest(level = 1))
                }

                currentGameId = response.id
                Log.d("MainActivity", "Game started: $response")

                if (response.status.name == "started") {
                    binding.startButton.visibility = View.GONE
                    val (newBoardState, turn) = parseFen(response.fen)
                    boardState = newBoardState
                    currentTurn = turn
                    playerColor = if (response.player == "white") 'w' else 'b'
                    updateChessBoard(response.fen)
                    playerColorTextView.text = if (response.player == "white") "Белые" else "Черные"

                    // Start streaming game state updates
                    streamBotGameState(currentGameId)
                }
                Toast.makeText(this@MainActivity, "Игра началась", Toast.LENGTH_SHORT).show()

            } catch (e: HttpException) {
                // Обработка ошибки при старте игры
                Log.d("MainActivity", "Error starting game: ${e.response()?.errorBody()?.string()}")
                Toast.makeText(this@MainActivity, "Ошибка при старте игры", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun streamBotGameState(gameId: String) {
        val request = Request.Builder()
            .url("https://lichess.org/api/bot/game/stream/$gameId")
            .addHeader("Authorization", "Bearer $authToken")
            .build()

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
        Log.d("MainActivity", "Handling game state update: $message")
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
                    Log.d("MainActivity", "Move response: $response")

                    withContext(Dispatchers.Main) {
                        if (response.ok) {
                            val (startCol, startRow, endCol, endRow) = parseMove(move)
                            if (boardState[startRow][startCol] == null) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Нет фигуры на выбранной клетке",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                boardState[endRow][endCol] = boardState[startRow][startCol]
                                boardState[startRow][startCol] = null
                                currentTurn = if (currentTurn == 'w') 'b' else 'w'

                                updateChessBoard(fenFromBoardState(boardState, currentTurn))

                                turnTextView.text =
                                    "Ход: ${if (currentTurn == 'w') "Белые" else "Черные"}"

                            }
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Ошибка при выполнении хода",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: HttpException) {
                    Log.e(
                        "MainActivity",
                        "Ошибка при выполнении хода: ${e.response()?.errorBody()?.string()}"
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Ошибка при выполнении хода",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Неизвестная ошибка: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Неизвестная ошибка", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }

    private fun updateChessBoard(fen: String) {
        val (boardState, turn) = parseFen(fen)
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
                        if (currentTurn == playerColor && (piece.isUpperCase() == (playerColor == 'w'))) {
                            selectedPiece = Pair(row, col)
                        }
                    }
                }

                cellFrame.setOnClickListener {
                    selectedPiece?.let { (startRow, startCol) ->
                        if (boardState[row][col] == null && boardState[startRow][startCol]?.isUpperCase() == (playerColor == 'w')) {
                            makeMove(
                                "${getChessNotation(startCol)}${8 - startRow}${
                                    getChessNotation(
                                        col
                                    )
                                }${8 - row}"
                            )
                            selectedPiece = null
                        }
                    }
                }
            }
        }

        turnTextView.text = "Ход: ${if (turn == 'w') "Белые" else "Черные"}"
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

    private fun fenFromBoardState(boardState: Array<Array<Char?>>, turn: Char): String {
        var fen = ""
        for (row in boardState) {
            var emptyCount = 0
            for (cell in row) {
                if (cell == null) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        fen += emptyCount
                        emptyCount = 0
                    }
                    fen += cell
                }
            }
            if (emptyCount > 0) {
                fen += emptyCount
            }
            fen += "/"
        }
        fen = fen.dropLast(1)
        fen += " $turn - - 0 1"
        return fen
    }
}
