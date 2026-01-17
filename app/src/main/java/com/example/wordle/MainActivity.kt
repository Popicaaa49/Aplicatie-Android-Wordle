package com.example.wordle

import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.GridLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private val rows = 6
    private val cols = 5
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private lateinit var grid: GridLayout
    private lateinit var cells: List<TextView>
    private lateinit var chapterSpinner: Spinner
    private lateinit var levelText: TextView
    private val keyButtons = mutableMapOf<Char, Button>()
    private val keyStates = mutableMapOf<Char, LetterState>()

    private var currentRow = 0
    private var currentCol = 0
    private var targetWord = ""
    private var isGameOver = false

    private val chapters = listOf(
        Chapter(
            "Animals",
            listOf(
                "TIGER", "ZEBRA", "HORSE", "SHEEP", "MOUSE",
                "PANDA", "KOALA", "SLOTH", "LLAMA", "CAMEL",
                "RHINO", "OTTER", "LEMUR", "GOOSE", "SHARK",
                "WHALE", "EAGLE", "SNAKE", "BISON", "HIPPO",
                "HYENA", "COBRA", "TAPIR", "MOOSE", "DINGO",
                "PUMAS", "SEALS", "LIONS", "WOLFS", "FOXES",
                "SKUNK", "RAVEN", "FINCH", "GUPPY"
            )
        ),
        Chapter(
            "Fruits",
            listOf(
                "APPLE", "GRAPE", "LEMON", "MANGO", "PEACH",
                "BERRY", "MELON", "GUAVA", "OLIVE", "PRUNE",
                "PEARS", "PLUMS", "DATES", "KIWIS", "LIMES",
                "PAPAW", "LYCHE", "SALAK", "CACAO"
            )
        )
    )

    private var currentChapterIndex = 0
    private var currentChapter = chapters.first()
    private var currentWordSet = currentChapter.words.toHashSet()
    private var levelNumber = 1
    private var isSpinnerInitialized = false
    private var pendingAdvance = false
    private val autoAdvanceRunnable = Runnable { startNewGame(advanceLevel = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        grid = findViewById(R.id.grid)
        chapterSpinner = findViewById(R.id.spinner_chapter)
        levelText = findViewById(R.id.level_text)
        setupGrid()
        setupKeyboard()
        setupChapterSpinner()

        findViewById<Button>(R.id.btn_new_game).setOnClickListener {
            startNewGame()
        }

        startChapter(0, announce = false)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isGameOver) {
            return super.onKeyUp(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                onDeletePressed()
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                onEnterPressed()
                return true
            }
        }

        val unicode = event?.unicodeChar ?: 0
        if (unicode != 0) {
            val letter = unicode.toChar().uppercaseChar()
            if (letter in 'A'..'Z') {
                onLetterPressed(letter)
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    private fun setupGrid() {
        val tileSize = resources.getDimensionPixelSize(R.dimen.tile_size)
        val tileTextSize = resources.getDimension(R.dimen.tile_text_size)
        val newCells = mutableListOf<TextView>()

        grid.removeAllViews()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cell = TextView(this)
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(row),
                    GridLayout.spec(col)
                )
                params.width = tileSize
                params.height = tileSize
                cell.layoutParams = params
                cell.gravity = android.view.Gravity.CENTER
                cell.setTextSize(TypedValue.COMPLEX_UNIT_PX, tileTextSize)
                cell.setTextColor(ContextCompat.getColor(this, R.color.tile_text))
                cell.setBackgroundResource(R.drawable.tile_default)
                newCells.add(cell)
                grid.addView(cell)
            }
        }

        cells = newCells
    }

    private fun setupKeyboard() {
        val keyboard = findViewById<ViewGroup>(R.id.keyboard)
        for (button in collectButtons(keyboard)) {
            val tag = button.tag?.toString() ?: continue
            when {
                tag == "ENTER" -> button.setOnClickListener { onEnterPressed() }
                tag == "DEL" -> button.setOnClickListener { onDeletePressed() }
                tag.length == 1 -> {
                    val letter = tag[0]
                    keyButtons[letter] = button
                    button.setOnClickListener { onLetterPressed(letter) }
                }
            }
        }
    }

    private fun setupChapterSpinner() {
        val chapterNames = chapters.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, chapterNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        chapterSpinner.adapter = adapter
        chapterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!isSpinnerInitialized) {
                    isSpinnerInitialized = true
                    return
                }
                if (position != currentChapterIndex) {
                    startChapter(position, announce = true)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // No-op.
            }
        }
    }

    private fun startChapter(index: Int, announce: Boolean) {
        currentChapterIndex = index
        currentChapter = chapters[index]
        currentWordSet = currentChapter.words.toHashSet()
        levelNumber = 1
        if (announce) {
            showToast("${currentChapter.name} chapter")
        }
        startNewGame()
    }

    private fun startNewGame(advanceLevel: Boolean = false) {
        grid.removeCallbacks(autoAdvanceRunnable)
        val shouldAdvance = advanceLevel || pendingAdvance
        pendingAdvance = false
        if (shouldAdvance) {
            levelNumber += 1
        }
        targetWord = currentChapter.words[Random.nextInt(currentChapter.words.size)]
        resetBoard()
        updateHeader()
    }

    private fun resetBoard() {
        currentRow = 0
        currentCol = 0
        isGameOver = false

        for (cell in cells) {
            cell.text = ""
            setCellState(cell, null)
        }

        keyStates.clear()
        for (letter in keyButtons.keys) {
            setKeyState(letter, null)
        }
    }

    private fun updateHeader() {
        levelText.text = getString(R.string.level_format, levelNumber)
    }

    private fun syncProgress() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            return
        }
        val user = auth.currentUser ?: return
        val payload = mapOf(
            "chapter" to currentChapter.name,
            "level" to levelNumber,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        firestore.collection("players")
            .document(user.uid)
            .set(payload, SetOptions.merge())
            .addOnFailureListener {
                showToast(getString(R.string.sync_failed))
            }
    }

    private fun onLetterPressed(letter: Char) {
        if (isGameOver || currentCol >= cols) {
            return
        }

        val cell = getCell(currentRow, currentCol)
        cell.text = letter.toString()
        setCellState(cell, null)
        currentCol += 1
    }

    private fun onDeletePressed() {
        if (isGameOver || currentCol <= 0) {
            return
        }

        currentCol -= 1
        val cell = getCell(currentRow, currentCol)
        cell.text = ""
        setCellState(cell, null)
    }

    private fun onEnterPressed() {
        if (isGameOver) {
            return
        }

        if (currentCol < cols) {
            showToast("Not enough letters")
            return
        }

        val guess = getRowString(currentRow)
        if (!currentWordSet.contains(guess)) {
            showToast("Not in word list")
            return
        }

        val results = evaluateGuess(guess, targetWord)
        for (col in 0 until cols) {
            val cell = getCell(currentRow, col)
            setCellState(cell, results[col])
            updateKeyState(guess[col], results[col])
        }

        if (guess == targetWord) {
            isGameOver = true
            showToast("Level $levelNumber complete!")
            syncProgress()
            pendingAdvance = true
            grid.postDelayed(autoAdvanceRunnable, 600)
            return
        }

        currentRow += 1
        currentCol = 0

        if (currentRow >= rows) {
            isGameOver = true
            showToast("Level failed. Word was $targetWord")
        }
    }

    private fun evaluateGuess(guess: String, target: String): List<LetterState> {
        val result = MutableList(cols) { LetterState.ABSENT }
        val counts = mutableMapOf<Char, Int>()

        for (i in target.indices) {
            val letter = target[i]
            counts[letter] = (counts[letter] ?: 0) + 1
        }

        for (i in 0 until cols) {
            val letter = guess[i]
            if (letter == target[i]) {
                result[i] = LetterState.CORRECT
                counts[letter] = (counts[letter] ?: 1) - 1
            }
        }

        for (i in 0 until cols) {
            if (result[i] == LetterState.CORRECT) {
                continue
            }
            val letter = guess[i]
            val available = counts[letter] ?: 0
            if (available > 0) {
                result[i] = LetterState.PRESENT
                counts[letter] = available - 1
            } else {
                result[i] = LetterState.ABSENT
            }
        }

        return result
    }

    private fun setCellState(cell: TextView, state: LetterState?) {
        when (state) {
            null -> {
                cell.setBackgroundResource(R.drawable.tile_default)
                cell.setTextColor(ContextCompat.getColor(this, R.color.tile_text))
            }
            LetterState.CORRECT -> {
                cell.setBackgroundResource(R.drawable.tile_correct)
                cell.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
            LetterState.PRESENT -> {
                cell.setBackgroundResource(R.drawable.tile_present)
                cell.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
            LetterState.ABSENT -> {
                cell.setBackgroundResource(R.drawable.tile_absent)
                cell.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
        }
    }

    private fun setKeyState(letter: Char, state: LetterState?) {
        val button = keyButtons[letter] ?: return
        when (state) {
            null -> {
                button.setBackgroundResource(R.drawable.key_default)
                button.setTextColor(ContextCompat.getColor(this, R.color.key_text))
            }
            LetterState.CORRECT -> {
                button.setBackgroundResource(R.drawable.key_correct)
                button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
            LetterState.PRESENT -> {
                button.setBackgroundResource(R.drawable.key_present)
                button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
            LetterState.ABSENT -> {
                button.setBackgroundResource(R.drawable.key_absent)
                button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
        }
    }

    private fun updateKeyState(letter: Char, newState: LetterState) {
        val current = keyStates[letter]
        if (current == null || newState.priority > current.priority) {
            keyStates[letter] = newState
            setKeyState(letter, newState)
        }
    }

    private fun getCell(row: Int, col: Int): TextView {
        return cells[row * cols + col]
    }

    private fun getRowString(row: Int): String {
        val builder = StringBuilder()
        for (col in 0 until cols) {
            builder.append(getCell(row, col).text.toString())
        }
        return builder.toString()
    }

    private fun collectButtons(root: View): List<Button> {
        val result = mutableListOf<Button>()
        when (root) {
            is Button -> result.add(root)
            is ViewGroup -> {
                for (i in 0 until root.childCount) {
                    result.addAll(collectButtons(root.getChildAt(i)))
                }
            }
        }
        return result
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private data class Chapter(val name: String, val words: List<String>)

    private enum class LetterState(val priority: Int) {
        ABSENT(0),
        PRESENT(1),
        CORRECT(2)
    }
}
