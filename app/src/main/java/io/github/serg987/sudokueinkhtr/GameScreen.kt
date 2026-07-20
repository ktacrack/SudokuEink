package io.github.serg987.sudokueinkhtr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import java.util.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape


enum class NotesMode {
    OFF, MANUAL, AUTO
}

object AppConfig {
    var badgeRow = 0
    var badgeCol = 2
    var handwritingStrokeThickness = 9f
}

@Composable
fun GameScreen(
    difficulty: Difficulty,
    mode: GameMode,
    isZenMode: Boolean = false,
    isDaily: Boolean = false,
    dailySudoku: SudokuGame? = null,
    onDailyComplete: ((Int) -> Unit)? = null,
    onBack: () -> Unit
){
    val context = LocalContext.current
    val strings = rememberStrings()
    
    // Force E-ink refresh on startup
    DisableCompositionAnimations()
    
    val dsegFontFamily = FontFamily(
        Font(R.font.dseg7_classic_mini_latin_700_italic, FontWeight.Normal)
    )

    var resetTrigger by remember { mutableIntStateOf(0) }  // mutableIntStateOf
    var resetTimerTrigger by remember(resetTrigger) { mutableIntStateOf(0) }

    val dailyGame = DailySudokuManager.generateDailySudoku() // Retorna daily sudoku

    // Intentar carregar partida guardada
    val savedGame = remember(resetTrigger, isZenMode) {
        if (resetTrigger == 0)
            GameStateManager.loadGame(context, mode, difficulty, isDaily, isZenMode)
        else null
    }

    val initialGame = remember(resetTrigger, isDaily) {
        if (savedGame != null) {
            val restoredBoard = savedGame.board.map { row ->
                row.map { savedCell ->
                    SudokuCell(
                        value = savedCell.value,
                        isFixed = savedCell.isFixed,
                        notes = savedCell.notes.toSet(),
                        isPencil = savedCell.isPencil
                    )
                }
            }
            SudokuGame(restoredBoard, savedGame.solution)
        } else {
            if (isDaily && dailySudoku != null) {
                // ✅ dailySudoku ja és un SudokuGame complet
                dailySudoku
            } else {
                // Generar sudoku normal
                SudokuGenerator.generate(difficulty)
            }
        }
    }

    var isPaused by remember(resetTrigger) { mutableStateOf(false) }
    var showPauseDialog by remember { mutableStateOf(false) }
    var shouldSaveOnExit by remember(resetTrigger) { mutableStateOf(true) }

    val scale = AdaptiveSizes.getScaleFactor()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val initialBoard = remember(resetTrigger) {
        if (savedGame != null) {
            savedGame.board.map { row ->
                row.map { savedCell ->
                    SudokuCell(
                        value = if (savedCell.isFixed) savedCell.value else 0,
                        isFixed = savedCell.isFixed,
                        notes = savedCell.notes.toSet()
                    )
                }
            }
        } else {
            initialGame.board.map { row -> row.map { it.copy() } }
        }
    }

    val solution = remember(resetTrigger) { initialGame.solution }

    var boardState by remember(resetTrigger) {
        mutableStateOf(
            initialGame.board.map { row ->
                row.map { cell -> cell.copy() }
            }
        )
    }
    
    val initialStrokes = remember(resetTrigger) {
        val loadedStrokes = mutableMapOf<Pair<Int, Int>, List<List<DrawingPoint>>>()
        savedGame?.board?.forEachIndexed { r, row ->
            row.forEachIndexed { c, cell ->
                if (cell.strokes.isNotEmpty()) {
                    loadedStrokes[Pair(r, c)] = cell.strokes
                }
            }
        }
        loadedStrokes.toMap()
    }

    var inkStrokes by remember(resetTrigger) { mutableStateOf(initialStrokes) }
    val inkStrokesHistory = remember(resetTrigger) { mutableStateListOf<Map<Pair<Int, Int>, List<List<DrawingPoint>>>>() }

    var hadErrorsDuringGame by remember { mutableStateOf(false) }
    var highlightErrors by remember(resetTrigger) { mutableStateOf(false) }
    var selectedCell by remember(resetTrigger) { mutableStateOf<Pair<Int, Int>?>(null) }
    var activeAction by remember(resetTrigger) { mutableStateOf<Int?>(null) } // 1..9 for digits, 0 for erase
    var moveCount by remember(resetTrigger) { mutableIntStateOf(savedGame?.moveCount ?: 0) }
    var showVictoryDialog by remember(resetTrigger) { mutableStateOf(false) }
    var showErrorDialog by remember(resetTrigger) { mutableStateOf(false) }
    var showTimeoutDialog by remember(resetTrigger) { mutableStateOf(false) }

    var isPencilMode by remember(resetTrigger) { mutableStateOf(true) }

    // ✅ ESBORRADES: showAchievementUnlocked i unlockedAchievement (no s'usaven)
    var pendingVictoryDialog by remember { mutableStateOf(false) }
    var pendingClearGame by remember { mutableStateOf(false) }
    var unlockedQueue by remember { mutableStateOf<List<Achievement>>(emptyList()) }
    var showingAchievement by remember { mutableStateOf<Achievement?>(null) }

    var startTimeOffset by remember(resetTrigger) { mutableIntStateOf(savedGame?.elapsedSeconds ?: 0) }
    var currentElapsedSeconds by remember(resetTrigger) { mutableIntStateOf(startTimeOffset) }
    var pausedAtSeconds by remember(resetTrigger) { mutableIntStateOf(startTimeOffset) }

    val timeLimitSeconds = remember(mode, difficulty) {
        if (mode == GameMode.ATTACK) {
            when (difficulty) {
                Difficulty.EASY -> 20 * 60
                Difficulty.MEDIUM -> 30 * 60
                Difficulty.HARD -> 45 * 60
            }
        } else {
            null
        }
    }

    // Shared with both InlineDrawingCanvas instances (portrait/landscape) below: true from
    // pen-down through the trailing ink flush (~1.5s per digit). The timer display holds
    // steady while true so its periodic recomposition can't race a pen-down and eat the
    // wet ink of the digit currently being written (EPD refreshes racing a pen-down can
    // eat that stroke's ink — see AGENTS.md). The elapsed-time accounting itself never
    // stops; only the displayed text is held, then jumps to the correct value on release.
    val penActiveState = remember { mutableStateOf(false) }

    val timerText = rememberTimer(
        resetTrigger = resetTrigger,
        startOffset = startTimeOffset,
        isPaused = isPaused || isZenMode,  // ✅ Pausar timer si Mode Zen
        resetTimer = resetTimerTrigger,
        holdDisplay = penActiveState.value,
        onTimeUpdate = { elapsedSeconds ->
            currentElapsedSeconds = elapsedSeconds
        }
    )

    val displayTimerText = if (timeLimitSeconds != null) {
        val remaining = (timeLimitSeconds - currentElapsedSeconds).coerceAtLeast(0)
        val minutes = remaining / 60
        val seconds = remaining % 60
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    } else {
        timerText
    }

    var hintsRemaining by rememberSaveable {
        mutableIntStateOf(
            savedGame?.hintsRemaining ?: when (difficulty) {
                Difficulty.EASY -> 5
                Difficulty.MEDIUM -> 3
                Difficulty.HARD -> 1
            }
        )
    }

    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0) {
            hintsRemaining = when (difficulty) {
                Difficulty.EASY -> 5
                Difficulty.MEDIUM -> 3
                Difficulty.HARD -> 1
            }
        }
    }

    var notesMode by remember(resetTrigger) { mutableStateOf(NotesMode.OFF) }
    var showAutoNotesDialog by remember { mutableStateOf(false) }
    val moveHistory = rememberSaveable { mutableStateListOf<List<List<SudokuCell>>>() }

    // Guardar automàticament
    LaunchedEffect(boardState, hintsRemaining) {
        if (!showVictoryDialog) {
            val savedState = SavedGameState(
                difficulty = difficulty.name,
                mode = mode.name,
                board = boardState.mapIndexed { r, row ->
                    row.mapIndexed { c, cell ->
                        SavedCell(
                            value = cell.value,
                            isFixed = cell.isFixed,
                            notes = cell.notes.toList(),
                            isPencil = cell.isPencil,
                            strokes = inkStrokes[Pair(r, c)] ?: emptyList()
                        )
                    }
                },
                solution = solution,
                elapsedSeconds = currentElapsedSeconds,
                hintsRemaining = hintsRemaining,
                moveCount = moveCount
            )
            GameStateManager.saveGame(context, savedState, isDaily, isZenMode)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (!showVictoryDialog && shouldSaveOnExit) {
                val savedState = SavedGameState(
                    difficulty = difficulty.name,
                    mode = mode.name,
                    board = boardState.mapIndexed { r, row ->
                        row.mapIndexed { c, cell ->
                            SavedCell(
                                value = cell.value,
                                isFixed = cell.isFixed,
                                notes = cell.notes.toList(),
                                isPencil = cell.isPencil,
                                strokes = inkStrokes[Pair(r, c)] ?: emptyList()
                            )
                        }
                    },
                    solution = solution,
                    elapsedSeconds = currentElapsedSeconds,
                    hintsRemaining = hintsRemaining,
                    moveCount = moveCount
                )
                GameStateManager.saveGame(context, savedState, isDaily, isZenMode)
            }
        }
    }

    LaunchedEffect(pendingClearGame) {
        if (pendingClearGame) {
            GameStateManager.clearGame(context, mode, difficulty, isDaily, isZenMode)
            pendingClearGame = false
        }
    }

    fun getValidNotesForCell(row: Int, col: Int): Set<Int> {
        val usedNumbers = mutableSetOf<Int>()

        for (c in 0 until 9) {
            if (boardState[row][c].value != 0) {
                usedNumbers.add(boardState[row][c].value)
            }
        }

        for (r in 0 until 9) {
            if (boardState[r][col].value != 0) {
                usedNumbers.add(boardState[r][col].value)
            }
        }

        val blockRow = (row / 3) * 3
        val blockCol = (col / 3) * 3
        for (r in blockRow until blockRow + 3) {
            for (c in blockCol until blockCol + 3) {
                if (boardState[r][c].value != 0) {
                    usedNumbers.add(boardState[r][c].value)
                }
            }
        }

        return (1..9).toSet() - usedNumbers
    }

    fun cleanAutoNotes(newBoard: List<List<SudokuCell>>): List<List<SudokuCell>> {
        if (notesMode != NotesMode.AUTO) return newBoard

        return newBoard.mapIndexed { row, rowList ->
            rowList.mapIndexed { col, cell ->
                if (cell.isFixed || cell.value != 0) {
                    cell
                } else if (cell.notes.isEmpty()) {
                    cell
                } else {
                    val usedNumbers = mutableSetOf<Int>()

                    for (c in 0 until 9) {
                        if (newBoard[row][c].value != 0) {
                            usedNumbers.add(newBoard[row][c].value)
                        }
                    }

                    for (r in 0 until 9) {
                        if (newBoard[r][col].value != 0) {
                            usedNumbers.add(newBoard[r][col].value)
                        }
                    }

                    val blockRow = (row / 3) * 3
                    val blockCol = (col / 3) * 3
                    for (r in blockRow until blockRow + 3) {
                        for (c in blockCol until blockCol + 3) {
                            if (newBoard[r][c].value != 0) {
                                usedNumbers.add(newBoard[r][c].value)
                            }
                        }
                    }

                    val validNotes = (1..9).toSet() - usedNumbers
                    cell.copy(notes = cell.notes.intersect(validNotes))
                }
            }
        }
    }

    if (showPauseDialog) {
        AlertDialog(
            onDismissRequest = { },
            shape = RoundedCornerShape((16 * scale).dp),
            containerColor = Color.White,
            titleContentColor = Color.Black,
            textContentColor = Color.Black,
            title = {
                Text(
                    text = strings.gamePaused,
                    fontSize = (32 * scale).sp,
                    lineHeight = (40 * scale).sp,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = strings.gamePausedMessage,
                    fontSize = (26 * scale).sp,
                    lineHeight = (30 * scale).sp,
                    color = Color.Black
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        startTimeOffset = pausedAtSeconds
                        showPauseDialog = false
                        isPaused = false
                    },
                    modifier = Modifier.fillMaxWidth().height((50 * scale).dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                    border = BorderStroke((2 * scale).dp, Color.Black),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(strings.resume, fontSize = (22 * scale).sp)
                }
            },
            dismissButton = null
        )
    }

    if (showAutoNotesDialog) {
        AlertDialog(
            onDismissRequest = { showAutoNotesDialog = false },
            shape = RoundedCornerShape((16 * scale).dp),
            containerColor = Color.White,
            titleContentColor = Color.Black,
            textContentColor = Color.Black,
            title = {
                Text(
                    text = strings.autoNotesTitle,
                    fontSize = (30 * scale).sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = strings.autoNotesExplanation,
                    fontSize = (26 * scale).sp,
                    lineHeight = (30 * scale).sp,
                    color = Color.Black
                )
            },
            confirmButton = {
                Button(
                    onClick = { showAutoNotesDialog = false },
                    modifier = Modifier.fillMaxWidth().height((50 * scale).dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                    border = BorderStroke((2 * scale).dp, Color.Black),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(strings.understood, fontSize = (24 * scale).sp)
                }
            }
        )
    }

    LaunchedEffect(pendingVictoryDialog) {
        if (pendingVictoryDialog) {
            val updatedStats = StatisticsManager.loadStatistics(context)
            val newlyUnlocked = mutableListOf<Achievement>()
            // ✅ ESBORRAT: var unlockedSomething = false (mai s'usava)

            AchievementManager.checkAchievements(
                context = context,
                stats = updatedStats,
                strings = strings,
                onNewAchievement = { newAchievement ->
                    newlyUnlocked.add(newAchievement)
                }
            )

            if (newlyUnlocked.isNotEmpty()) {
                unlockedQueue = newlyUnlocked
                showingAchievement = unlockedQueue.first()
            } else {
                showVictoryDialog = true
                pendingClearGame = true
                pendingVictoryDialog = false
            }
        }
    }

    if (showingAchievement != null) {
        AlertDialog(
            onDismissRequest = { /* forcem a clicar botó */ },
            icon = {
                Icon(
                    Icons.Default.EmojiEvents,
                    null,
                    modifier = Modifier.size((64 * scale).dp),
                    tint = Color.Black
                )
            },
            title = { Text(strings.achievementUnlocked, color = Color.Black) },
            text = {
                Column {
                    Text(
                        showingAchievement!!.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = (20 * scale).sp
                    )
                    Text(
                        showingAchievement!!.description,
                        fontSize = (16 * scale).sp,
                        color = Color.Black
                    )
                }
            },
            shape = RoundedCornerShape((16 * scale).dp),
            containerColor = Color.White,
            titleContentColor = Color.Black,
            textContentColor = Color.Black,
            confirmButton = {
                Button(
                    onClick = {
                        unlockedQueue = unlockedQueue.drop(1)

                        if (unlockedQueue.isNotEmpty()) {
                            showingAchievement = unlockedQueue.first()
                        } else {
                            showingAchievement = null
                            showVictoryDialog = true
                            pendingClearGame = true
                            pendingVictoryDialog = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height((50 * scale).dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                    border = BorderStroke((2 * scale).dp, Color.Black),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(strings.awesome, fontSize = (22 * scale).sp)
                }
            }
        )
    }

    fun updateBoard(newBoard: List<List<SudokuCell>>) {
        moveHistory.add(boardState)
        inkStrokesHistory.add(inkStrokes)
        boardState = cleanAutoNotes(newBoard)
        highlightErrors = false

        // ✅ NOU: Incrementar moviments en Mode Zen
        if (isZenMode) {
            moveCount++
        }
    }

    LaunchedEffect(timeLimitSeconds, currentElapsedSeconds, showVictoryDialog) {
        if (timeLimitSeconds != null &&
            !showVictoryDialog &&
            currentElapsedSeconds >= timeLimitSeconds
        ) {
            isPaused = true
            showTimeoutDialog = true
            shouldSaveOnExit = false
        }
    }

    LaunchedEffect(boardState) {
        val complete = boardState.all { row -> row.all { it.value != 0 } }
        if (complete && !showVictoryDialog) {
            val correct = boardState.mapIndexed { r, row ->
                row.mapIndexed { c, cell -> cell.value == solution[r][c] }
            }.all { it.all { it } }

            if (correct) {
                isPaused = true

                val hintsUsed = when (difficulty) {
                    Difficulty.EASY -> 5 - hintsRemaining
                    Difficulty.MEDIUM -> 3 - hintsRemaining
                    Difficulty.HARD -> 1 - hintsRemaining
                }

                if (!isDaily) {
                    StatisticsManager.recordCompletion(
                        context, difficulty, mode, currentElapsedSeconds,
                        hintsUsed = hintsUsed,
                        errorsCount = if (hadErrorsDuringGame) 1 else 0,
                        isDaily = false,
                        isZenMode = isZenMode
                    )
                } else {
                    DailySudokuManager.markDailyAsPlayed(context, currentElapsedSeconds)
                    DailySudokuManager.recordDailyCompletion(context)
                    DailySudokuManager.markTodayAsZenMode(context, isZenMode)
                    StatisticsManager.recordCompletion(
                        context, difficulty, mode, currentElapsedSeconds,
                        hintsUsed = hintsUsed,
                        errorsCount = if (hadErrorsDuringGame) 1 else 0,
                        isDaily = true,
                        isZenMode = isZenMode
                    )
                }
                pendingVictoryDialog = true

            } else {
                hadErrorsDuringGame = true
                showErrorDialog = true
            }
        }
    }

    if (isLandscape) {
                // LAYOUT HORITZONTAL
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding((16 * scale).dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1.5f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.Start
            ) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(x = (10 * scale).dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Centre: Títol Daily (o buit)
                    if (isDaily) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = strings.dailySudokuTitle,
                                fontSize = (28 * scale).sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF9800)
                            )
                            Text(
                                text = DailySudokuManager.getTodayFormatted(),
                                fontSize = (18 * scale).sp,
                                color = Color.Gray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height((8 * scale).dp))

                // Títol dificultat + Timer (sense el bloc isDaily que estava aquí)
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when (difficulty) {
                            Difficulty.EASY -> strings.difficultyEasy
                            Difficulty.MEDIUM -> strings.difficultyMedium
                            Difficulty.HARD -> strings.difficultyHard
                        },
                        color = Color.Black,
                        modifier = Modifier.padding(bottom = (8 * scale).dp),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = (24 * scale).sp)
                    )
                    // Timer
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(top = 0.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (isPaused) {
                                    startTimeOffset = pausedAtSeconds
                                    isPaused = false
                                } else {
                                    isPaused = true
                                    pausedAtSeconds = currentElapsedSeconds
                                    showPauseDialog = true
                                }
                            },
                            modifier = Modifier.size((36 * scale).dp)
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isPaused) "Reprendre" else "Pausar",
                                modifier = Modifier.size((24 * scale).dp),
                                tint = Color.Black
                            )
                        }

                        //  MODIFICAT: condicional per Mode Zen
                        if (isZenMode) {
                            Text(
                                text = "${strings.moves}: $moveCount",
                                fontSize = (24 * scale).sp,
                                color = Color.Black
                            )
                        } else {
                            Text(
                                text = displayTimerText,
                                fontSize = (19 * scale).sp,
                                fontFamily = dsegFontFamily,
                                color = Color.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(0.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        SudokuBoard(
                            board = boardState,
                            solution = solution,
                            highlightErrors = highlightErrors,
                            selectedCell = selectedCell,
                            onCellClick = { row, col ->
                                if (!isPaused && !boardState[row][col].isFixed) {
                                    selectedCell = Pair(row, col)
                                    if (activeAction != null) {
                                        if (activeAction in 1..9) {
                                            val number = activeAction!!
                                            updateBoard(
                                                boardState.mapIndexed { r, rowList ->
                                                    rowList.mapIndexed { c, cell ->
                                                        if (r == row && c == col) {
                                                            when (notesMode) {
                                                                NotesMode.MANUAL -> {
                                                                    val newNotes = if (cell.notes.contains(number)) cell.notes - number else cell.notes + number
                                                                    cell.copy(notes = newNotes)
                                                                }
                                                                NotesMode.AUTO, NotesMode.OFF -> cell.copy(value = number, notes = emptySet(), isPencil = false)
                                                            }
                                                        } else cell
                                                    }
                                                }
                                            )
                                            if (notesMode != NotesMode.MANUAL) {
                                                val newStrokes = inkStrokes.toMutableMap()
                                                newStrokes.remove(Pair(row, col))
                                                inkStrokes = newStrokes
                                            }
                                        } else if (activeAction == 0) {
                                            updateBoard(
                                                boardState.mapIndexed { r, rowList ->
                                                    rowList.mapIndexed { c, cell ->
                                                        if (r == row && c == col) cell.copy(value = 0, notes = emptySet(), isPencil = false) else cell
                                                    }
                                                }
                                            )
                                            val newStrokes = inkStrokes.toMutableMap()
                                            newStrokes.remove(Pair(row, col))
                                            inkStrokes = newStrokes
                                        }
                                        activeAction = null
                                    }
                                }
                            },
                            onCellLongClick = { row, col ->
                                if (!isPaused && notesMode == NotesMode.AUTO && !boardState[row][col].isFixed) {
                                    val validNotes = getValidNotesForCell(row, col)
                                    updateBoard(
                                        boardState.mapIndexed { r, rowList ->
                                            rowList.mapIndexed { c, cell ->
                                                if (r == row && c == col) {
                                                    cell.copy(notes = validNotes, value = 0)
                                                } else {
                                                    cell
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        )
                        if (isPencilMode) {
                            InlineDrawingCanvas(
                                modifier = Modifier.matchParentSize(),
                                inkStrokes = inkStrokes,
                                onInkStrokesChanged = { inkStrokes = it },
                                notesMode = notesMode,
                                onDigitRecognized = { digit, row, col ->
                                    if (!isPaused && !boardState[row][col].isFixed && digit in 1..9) {
                                        updateBoard(
                                            boardState.mapIndexed { r, rowList ->
                                                rowList.mapIndexed { c, cell ->
                                                    if (r == row && c == col) {
                                                        when (notesMode) {
                                                            NotesMode.MANUAL -> {
                                                                val newNotes = if (cell.notes.contains(digit)) cell.notes - digit else cell.notes + digit
                                                                cell.copy(notes = newNotes)
                                                            }
                                                            NotesMode.AUTO, NotesMode.OFF -> cell.copy(value = digit, notes = emptySet(), isPencil = true)
                                                        }
                                                    } else cell
                                                }
                                            }
                                        )
                                    }
                                },
                                onClearCell = { row, col ->
                                    if (!isPaused && !boardState[row][col].isFixed) {
                                        updateBoard(
                                            boardState.mapIndexed { r, rowList ->
                                                rowList.mapIndexed { c, cell ->
                                                    if (r == row && c == col) cell.copy(value = 0, isPencil = false) else cell
                                                }
                                            }
                                        )
                                    }
                                },
                                isCellFilledWithPencil = { r, c -> boardState[r][c].isPencil && boardState[r][c].value != 0 },
                                isCellFixed = { r, c -> boardState[r][c].isFixed },
                                penActiveState = penActiveState
                            )
                        }
                    }

                }
            }

            Spacer(modifier = Modifier.width((16 * scale).dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height((36 * scale).dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)
                ) {
                    if(!isDaily) {
                        Button(
                            onClick = {
                                shouldSaveOnExit = false
                                GameStateManager.clearGame(context, mode, difficulty, isDaily, isZenMode)
                                resetTrigger++
                                hadErrorsDuringGame = false
                            },
                            modifier = Modifier.weight(1f).height((50 * scale).dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                            border = BorderStroke((2 * scale).dp, Color.Black)
                        ) {
                            Text(strings.newGame, fontSize = (20 * scale).sp)
                        }
                    }

                    Button(
                        onClick = {
                            if (!isPaused) {
                                moveHistory.clear()
                                inkStrokesHistory.clear()
                                inkStrokes = initialStrokes
                                boardState = initialBoard.map { row -> row.map { cell -> cell.copy() } }
                                selectedCell = null
                                activeAction = null
                                moveCount = 0  // Resetear també moviments
                                hadErrorsDuringGame = false
                                highlightErrors = false
                                hintsRemaining = when (difficulty) {
                                    Difficulty.EASY -> 5
                                    Difficulty.MEDIUM -> 3
                                    Difficulty.HARD -> 1
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height((50 * scale).dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                        border = BorderStroke((2 * scale).dp, Color.Black)
                    ) {
                        Text(strings.reset, fontSize = (20 * scale).sp)
                    }
                }

                Spacer(modifier = Modifier.height((20 * scale).dp))

                // Números grid 3x3
                Column {
                    for (rowNum in 0 until 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            for (colNum in 0 until 3) {
                                val number = rowNum * 3 + colNum + 1
                                Button(
                                    onClick = {
                                        if (!isPaused) {
                                            if (activeAction == number) activeAction = null else activeAction = number
                                        }
                                    },
                                    modifier = Modifier
                                        .size((50 * scale).dp)
                                        .padding((2 * scale).dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.Transparent,
                                        contentColor = if (activeAction == number) Color.Black else Color.Gray
                                    ),
                                    border = BorderStroke((2 * scale).dp, if (activeAction == number) Color.Black else Color.Gray),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(text = number.toString(), fontSize = (32 * scale).sp)
                                }
                            }
                        }
                        if (rowNum < 2) {
                            Spacer(modifier = Modifier.height((8 * scale).dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height((24 * scale).dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)
                ) {
                    Button(
                        onClick = {
                            if (!isPaused) {
                                isPencilMode = !isPencilMode
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height((50 * scale).dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = if (isPencilMode) Color.Black else Color.Gray
                        ),
                        border = BorderStroke((2 * scale).dp, if (isPencilMode) Color.Black else Color.Gray)
                    ) {
                        Text(
                            if (isPencilMode) strings.pencilOn else strings.pencilOff,
                            fontSize = (20 * scale).sp
                        )
                    }

                    BadgedBox(
                        badge = {
                            if (notesMode == NotesMode.AUTO) {
                                Badge(
                                    modifier = Modifier
                                        .offset(x = (-12 * scale).dp, y = (4 * scale).dp)
                                        .clickable { showAutoNotesDialog = true }
                                        .border((2 * scale).dp, Color.Black, RoundedCornerShape(50)),
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                ) {
                                    Text(
                                        text = "i",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = (16 * scale).sp,
                                        modifier = Modifier.padding((2 * scale).dp)
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Button(
                            onClick = {
                                if (!isPaused) {
                                    notesMode = when (notesMode) {
                                        NotesMode.OFF -> NotesMode.MANUAL
                                        NotesMode.MANUAL -> NotesMode.AUTO
                                        NotesMode.AUTO -> NotesMode.OFF
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((50 * scale).dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = if (notesMode == NotesMode.OFF) Color.Gray else Color.Black
                            ),
                            border = BorderStroke((2 * scale).dp, if (notesMode == NotesMode.OFF) Color.Gray else Color.Black)
                        ) {
                            Text(
                                text = when (notesMode) {
                                    NotesMode.OFF -> strings.notesOff
                                    NotesMode.MANUAL -> strings.notesManual
                                    NotesMode.AUTO -> strings.notesAuto
                                },
                                fontSize = (20 * scale).sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height((8 * scale).dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)
                ) {
                    Button(
                        onClick = {
                            if (!isPaused) {
                                if (activeAction == 0) activeAction = null else activeAction = 0
                            }
                        },
                        modifier = Modifier.weight(1f).height((50 * scale).dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = if (activeAction == 0) Color.Black else Color.Gray
                        ),
                        border = BorderStroke((2 * scale).dp, if (activeAction == 0) Color.Black else Color.Gray)
                    ) {
                        Text(strings.erase, fontSize = (20 * scale).sp)
                    }

                    Button(
                        onClick = {
                            if (!isPaused && hintsRemaining > 0) {
                                val emptyCells = mutableListOf<Pair<Int, Int>>()
                                for (r in 0 until 9) {
                                    for (c in 0 until 9) {
                                        if (boardState[r][c].value == 0 && !boardState[r][c].isFixed) {
                                            emptyCells.add(Pair(r, c))
                                        }
                                    }
                                }
                                if (emptyCells.isNotEmpty()) {
                                    val (r, c) = emptyCells.random()
                                    updateBoard(
                                        boardState.mapIndexed { row, rowList ->
                                            rowList.mapIndexed { col, cell ->
                                                if (row == r && col == c) {
                                                    cell.copy(value = solution[r][c], notes = emptySet())
                                                } else {
                                                    cell
                                                }
                                            }
                                        }
                                    )
                                    val newStrokes = inkStrokes.toMutableMap()
                                    newStrokes.remove(Pair(r, c))
                                    inkStrokes = newStrokes
                                    hintsRemaining--
                                }
                            }
                        },
                        enabled = hintsRemaining > 0,
                        modifier = Modifier
                            .weight(1f)
                            .height((50 * scale).dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.Black,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = Color.Gray
                        ),
                        border = BorderStroke((2 * scale).dp, if (hintsRemaining > 0) Color.Black else Color.Gray)
                    ) {
                        Text("${strings.hint} ($hintsRemaining)", fontSize = (20 * scale).sp)
                    }
                }

                Spacer(modifier = Modifier.height((8 * scale).dp))

                Button(
                    onClick = {
                        if (!isPaused && moveHistory.isNotEmpty()) {
                            boardState = moveHistory.removeAt(moveHistory.lastIndex)
                            if (inkStrokesHistory.isNotEmpty()) {
                                inkStrokes = inkStrokesHistory.removeAt(inkStrokesHistory.lastIndex)
                            }
                            highlightErrors = false
                        }
                    },
                    enabled = moveHistory.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((50 * scale).dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.Black,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = Color.Gray
                    ),
                    border = BorderStroke((2 * scale).dp, if (moveHistory.isNotEmpty()) Color.Black else Color.Gray)
                ) {
                    Text(strings.undo, fontSize = (20 * scale).sp)
                }
            }
        }
    } else {
        // LAYOUT VERTICAL
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding((16 * scale).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // FILA 1: Nou Joc (o Títol Daily) | Nivell+Timer | Reiniciar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Esquerra: Nou Joc (o Títol Daily)
                if (isDaily) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy((0 * scale).dp),
                        modifier = Modifier.width((180 * scale).dp)
                    ) {
                        Text(
                            text = strings.dailySudokuTitle,
                            fontSize = (24 * scale).sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9800)
                        )
                        Text(
                            text = DailySudokuManager.getTodayFormatted(),
                            fontSize = (20 * scale).sp,
                            lineHeight = (16 * scale).sp,
                            color = Color.DarkGray
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            shouldSaveOnExit = false
                            GameStateManager.clearGame(context, mode, difficulty, isDaily, isZenMode)
                            resetTrigger++
                            hadErrorsDuringGame = false
                        },
                        modifier = Modifier
                            .height((50 * scale).dp)
                            .width((180 * scale).dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                        border = BorderStroke((2 * scale).dp, Color.Black),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(strings.newGame, fontSize = (20 * scale).sp)
                    }
                }

                // Centre: Nivell + Timer
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when (difficulty) {
                            Difficulty.EASY -> strings.difficultyEasy
                            Difficulty.MEDIUM -> strings.difficultyMedium
                            Difficulty.HARD -> strings.difficultyHard
                        },
                        color = Color.Black,
                        modifier = Modifier.padding(bottom = (8 * scale).dp),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = (24 * scale).sp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "Reprendre" else "Pausar",
                            tint = Color.Black,
                            modifier = Modifier
                                .size((24 * scale).dp)
                                .clickable {
                                    if (isPaused) {
                                        startTimeOffset = pausedAtSeconds
                                        isPaused = false
                                    } else {
                                        isPaused = true
                                        pausedAtSeconds = currentElapsedSeconds
                                        showPauseDialog = true
                                    }
                                }
                        )
                        Spacer(modifier = Modifier.width((8 * scale).dp))
                        if (isZenMode) {
                            Text(
                                text = "${strings.moves}: $moveCount",
                                fontSize = (24 * scale).sp,
                                color = Color.Black
                            )
                        } else {
                            Text(
                                text = displayTimerText,
                                fontSize = (19 * scale).sp,
                                fontFamily = dsegFontFamily,
                                color = Color.Black
                            )
                        }
                    }
                }

                // Dreta: Reiniciar
                Button(
                    onClick = {
                        if (!isPaused) {
                            moveHistory.clear()
                            inkStrokesHistory.clear()
                            inkStrokes = initialStrokes
                            boardState = initialBoard.map { row -> row.map { cell -> cell.copy() } }
                            selectedCell = null
                            activeAction = null
                            moveCount = 0
                            hadErrorsDuringGame = false
                            highlightErrors = false
                            hintsRemaining = when (difficulty) {
                                Difficulty.EASY -> 5
                                Difficulty.MEDIUM -> 3
                                Difficulty.HARD -> 1
                            }
                        }
                    },
                    modifier = Modifier
                        .height((50 * scale).dp)
                        .width((180 * scale).dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                    border = BorderStroke((2 * scale).dp, Color.Black),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(strings.reset, fontSize = (20 * scale).sp)
                }
            }

            Spacer(modifier = Modifier.height((12 * scale).dp))

            // FILA 3: Tauler
            Box(contentAlignment = Alignment.Center) {
                SudokuBoard(
                    board = boardState,
                    solution = solution,
                    highlightErrors = highlightErrors,
                    selectedCell = selectedCell,
                    onCellClick = { row, col ->
                        if (!isPaused && !boardState[row][col].isFixed) {
                            selectedCell = Pair(row, col)
                            if (activeAction != null) {
                                if (activeAction in 1..9) {
                                    val number = activeAction!!
                                    updateBoard(
                                        boardState.mapIndexed { r, rowList ->
                                            rowList.mapIndexed { c, cell ->
                                                if (r == row && c == col) {
                                                    when (notesMode) {
                                                        NotesMode.MANUAL -> {
                                                            val newNotes = if (cell.notes.contains(number)) cell.notes - number else cell.notes + number
                                                            cell.copy(notes = newNotes)
                                                        }
                                                        NotesMode.AUTO, NotesMode.OFF -> cell.copy(value = number, notes = emptySet(), isPencil = false)
                                                    }
                                                } else cell
                                            }
                                        }
                                    )
                                    if (notesMode != NotesMode.MANUAL) {
                                        val newStrokes = inkStrokes.toMutableMap()
                                        newStrokes.remove(Pair(row, col))
                                        inkStrokes = newStrokes
                                    }
                                } else if (activeAction == 0) {
                                    updateBoard(
                                        boardState.mapIndexed { r, rowList ->
                                            rowList.mapIndexed { c, cell ->
                                                if (r == row && c == col) cell.copy(value = 0, notes = emptySet(), isPencil = false) else cell
                                            }
                                        }
                                    )
                                    val newStrokes = inkStrokes.toMutableMap()
                                    newStrokes.remove(Pair(row, col))
                                    inkStrokes = newStrokes
                                }
                                activeAction = null
                            }
                        }
                    },
                    onCellLongClick = { row, col ->
                        if (!isPaused && notesMode == NotesMode.AUTO && !boardState[row][col].isFixed) {
                            val validNotes = getValidNotesForCell(row, col)
                            updateBoard(boardState.mapIndexed { r, rowList ->
                                rowList.mapIndexed { c, cell ->
                                    if (r == row && c == col) cell.copy(notes = validNotes, value = 0) else cell
                                }
                            })
                        }
                    }
                )
                if (isPencilMode) {
                    InlineDrawingCanvas(
                        modifier = Modifier.matchParentSize(),
                        inkStrokes = inkStrokes,
                        onInkStrokesChanged = { inkStrokes = it },
                        notesMode = notesMode,
                        onDigitRecognized = { digit, row, col ->
                            if (!isPaused && !boardState[row][col].isFixed && digit in 1..9) {
                                updateBoard(
                                    boardState.mapIndexed { r, rowList ->
                                        rowList.mapIndexed { c, cell ->
                                            if (r == row && c == col) {
                                                when (notesMode) {
                                                    NotesMode.MANUAL -> {
                                                        val newNotes = if (cell.notes.contains(digit)) cell.notes - digit else cell.notes + digit
                                                        cell.copy(notes = newNotes)
                                                    }
                                                    NotesMode.AUTO, NotesMode.OFF -> cell.copy(value = digit, notes = emptySet(), isPencil = true)
                                                }
                                            } else cell
                                        }
                                    }
                                )
                            }
                        },
                        onClearCell = { row, col ->
                            if (!isPaused && !boardState[row][col].isFixed) {
                                updateBoard(
                                    boardState.mapIndexed { r, rowList ->
                                        rowList.mapIndexed { c, cell ->
                                            if (r == row && c == col) cell.copy(value = 0, isPencil = false) else cell
                                        }
                                    }
                                )
                            }
                        },
                        isCellFilledWithPencil = { r, c -> boardState[r][c].isPencil && boardState[r][c].value != 0 },
                        isCellFixed = { r, c -> boardState[r][c].isFixed },
                        penActiveState = penActiveState
                    )
                }
            }

            Spacer(modifier = Modifier.height((10 * scale).dp))

            // Fila de números
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = (3 * scale).dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (number in 1..9) {
                    Button(
                        onClick = {
                            if (!isPaused) {
                                if (activeAction == number) activeAction = null else activeAction = number
                            }
                        },
                        modifier = Modifier.size((52 * scale).dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = if (activeAction == number) Color.Black else Color.Gray
                        ),
                        border = BorderStroke((2 * scale).dp, if (activeAction == number) Color.Black else Color.Gray),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(text = number.toString(), fontSize = (40 * scale).sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height((12 * scale).dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = {
                        if (!isPaused) {
                            isPencilMode = !isPencilMode
                        }
                    },
                    modifier = Modifier
                        .height((50 * scale).dp)
                        .weight(1f)
                        .padding(horizontal = (1 * scale).dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = if (isPencilMode) Color.Black else Color.Gray
                    ),
                    border = BorderStroke((2 * scale).dp, if (isPencilMode) Color.Black else Color.Gray)
                ) {
                    Text(
                        if (isPencilMode) strings.pencilOn else strings.pencilOff,
                        fontSize = (20 * scale).sp
                    )
                }

                BadgedBox(
                    badge = {
                        if (notesMode == NotesMode.AUTO) {
                            Badge(
                                modifier = Modifier
                                    .offset(x = (-12 * scale).dp, y = (4 * scale).dp)
                                    .clickable { showAutoNotesDialog = true }
                                    .border((2 * scale).dp, Color.Black, RoundedCornerShape(50)),
                                containerColor = Color.White,
                                contentColor = Color.Black
                            ) {
                                Text(
                                    text = "i",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (16 * scale).sp,
                                    modifier = Modifier.padding((2 * scale).dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Button(
                        onClick = {
                            if (!isPaused) {
                                notesMode = when (notesMode) {
                                    NotesMode.OFF -> NotesMode.MANUAL
                                    NotesMode.MANUAL -> NotesMode.AUTO
                                    NotesMode.AUTO -> NotesMode.OFF
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((50 * scale).dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = if (notesMode == NotesMode.OFF) Color.Gray else Color.Black
                        ),
                        border = BorderStroke((2 * scale).dp, if (notesMode == NotesMode.OFF) Color.Gray else Color.Black)
                    ) {
                        Text(
                            text = when (notesMode) {
                                NotesMode.OFF -> strings.notesOff
                                NotesMode.MANUAL -> strings.notesManual
                                NotesMode.AUTO -> strings.notesAuto
                            },
                            fontSize = (20 * scale).sp
                        )
                    }
                }

                Button(
                    onClick = {
                        if (!isPaused) {
                            if (activeAction == 0) activeAction = null else activeAction = 0
                        }
                    },
                    modifier = Modifier
                        .height((50 * scale).dp)
                        .weight(1f)
                        .padding(horizontal = (1 * scale).dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = if (activeAction == 0) Color.Black else Color.Gray
                    ),
                    border = BorderStroke((2 * scale).dp, if (activeAction == 0) Color.Black else Color.Gray)
                ) {
                    Text(strings.erase, fontSize = (20 * scale).sp)
                }

                Button(
                    onClick = {
                        if (!isPaused && hintsRemaining > 0) {
                            val emptyCells = mutableListOf<Pair<Int, Int>>()
                            for (r in 0 until 9) {
                                for (c in 0 until 9) {
                                    if (boardState[r][c].value == 0 && !boardState[r][c].isFixed) {
                                        emptyCells.add(Pair(r, c))
                                    }
                                }
                            }
                            if (emptyCells.isNotEmpty()) {
                                val (r, c) = emptyCells.random()
                                updateBoard(
                                    boardState.mapIndexed { row, rowList ->
                                        rowList.mapIndexed { col, cell ->
                                            if (row == r && col == c) {
                                                cell.copy(value = solution[r][c], notes = emptySet())
                                            } else {
                                                cell
                                            }
                                        }
                                    }
                                )
                                val newStrokes = inkStrokes.toMutableMap()
                                newStrokes.remove(Pair(r, c))
                                inkStrokes = newStrokes
                                hintsRemaining--
                            }
                        }
                    },
                    enabled = hintsRemaining > 0,
                    modifier = Modifier
                        .height((50 * scale).dp)
                        .weight(1f)
                        .padding(horizontal = (1 * scale).dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.Black,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = Color.Gray
                    ),
                    border = BorderStroke((2 * scale).dp, if (hintsRemaining > 0) Color.Black else Color.Gray)
                ) {
                    Text("${strings.hint} ($hintsRemaining)", fontSize = (20 * scale).sp)
                }

                Button(
                    onClick = {
                        if (!isPaused && moveHistory.isNotEmpty()) {
                            boardState = moveHistory.removeAt(moveHistory.lastIndex)
                            if (inkStrokesHistory.isNotEmpty()) {
                                inkStrokes = inkStrokesHistory.removeAt(inkStrokesHistory.lastIndex)
                            }
                            highlightErrors = false
                        }
                    },
                    enabled = moveHistory.isNotEmpty(),
                    modifier = Modifier
                        .height((50 * scale).dp)
                        .weight(1f)
                        .padding(horizontal = (1 * scale).dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.Black,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = Color.Gray
                    ),
                    border = BorderStroke((2 * scale).dp, if (moveHistory.isNotEmpty()) Color.Black else Color.Gray)
                ) {
                    Text(strings.undo, fontSize = (20 * scale).sp)
                }
            }
        }
    }





    // Diàleg d'error
    if (showErrorDialog) {
        LaunchedEffect(Unit) {
            pausedAtSeconds = currentElapsedSeconds
            isPaused = true
        }

        AlertDialog(
            onDismissRequest = {
                showErrorDialog = false
                startTimeOffset = pausedAtSeconds
                isPaused = false
            },
            shape = RoundedCornerShape((16 * scale).dp),
            containerColor = Color.White,
            titleContentColor = Color.Black,
            textContentColor = Color.Black,
            title = {
                Text(
                    text = strings.error,
                    fontSize = (32 * scale).sp,
                    lineHeight = (40 * scale).sp,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = strings.errorMessage,
                    fontSize = (24 * scale).sp,
                    lineHeight = (32 * scale).sp,
                    color = Color.Black
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showErrorDialog = false
                        startTimeOffset = pausedAtSeconds
                        isPaused = false
                        highlightErrors = true
                    },
                    modifier = Modifier.fillMaxWidth().height((50 * scale).dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                    border = BorderStroke((2 * scale).dp, Color.Black),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(strings.review, fontSize = (22 * scale).sp)
                }
            }
        )
    }

    // Diàleg de timeout (Time Attack)
    if (showTimeoutDialog) {
        AlertDialog(
            onDismissRequest = {
                showTimeoutDialog = false
                isPaused = false
            },
            shape = RoundedCornerShape((16 * scale).dp),
            containerColor = Color.White,
            titleContentColor = Color.Black,
            textContentColor = Color.Black,
            title = {
                Text(
                    text = strings.timeUp,
                    fontSize = (32 * scale).sp,
                    lineHeight = (40 * scale).sp,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = strings.timeUpMessage,
                    fontSize = (24 * scale).sp,
                    lineHeight = (32 * scale).sp,
                    color = Color.Black
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)
                ) {
                    if(!isDaily) {
                        Button(
                            onClick = {
                                showTimeoutDialog = false
                                shouldSaveOnExit = false
                                GameStateManager.clearGame(context, mode, difficulty, isDaily, isZenMode)
                                resetTrigger++
                                hadErrorsDuringGame = false
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height((50 * scale).dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                            border = BorderStroke((2 * scale).dp, Color.Black),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(strings.newGame, fontSize = (22 * scale).sp)
                        }
                    }
                }
            },
            dismissButton = null
        )
    }

    // Diàleg de victòria
    if (showVictoryDialog) {
        AlertDialog(
            onDismissRequest = { showVictoryDialog = false },
            shape = RoundedCornerShape((16 * scale).dp),
            containerColor = Color.White,
            titleContentColor = Color.Black,
            textContentColor = Color.Black,
            title = {
                Text(
                    text = strings.congratulations,
                    fontSize = (32 * scale).sp,
                    lineHeight = (40 * scale).sp,
                    color = Color.Black
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isZenMode) {
                        Text(
                            text = "${strings.completed}\n\n${strings.moves}: $moveCount",
                            fontSize = (24 * scale).sp,
                            lineHeight = (32 * scale).sp,
                            textAlign = TextAlign.Center,
                            color = Color.Black
                        )
                    } else {
                        Text(
                            text = "${strings.completed}\n\n${strings.time} $displayTimerText",
                            fontSize = (24 * scale).sp,
                            lineHeight = (32 * scale).sp,
                            textAlign = TextAlign.Center,
                            color = Color.Black
                        )

                        if (isDaily) {
                            Spacer(modifier = Modifier.height((16 * scale).dp))
                            Text(
                                text = "${strings.currentStreak}: ${DailySudokuManager.getCurrentStreak(context)} ${strings.days}",
                                fontSize = (20 * scale).sp,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)
                ) {
                    if(!isDaily) {
                        Button(
                            onClick = {
                                showVictoryDialog = false
                                shouldSaveOnExit = false
                                GameStateManager.clearGame(context, mode, difficulty, isDaily, isZenMode)
                                resetTrigger++
                                hadErrorsDuringGame = false
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height((50 * scale).dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black),
                            border = BorderStroke((2 * scale).dp, Color.Black),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(strings.newGame, fontSize = (22 * scale).sp)
                        }
                    }
                }
            },
            dismissButton = null
        )
    }
}

@Composable
fun SudokuBoard(
    board: List<List<SudokuCell>>,
    solution: List<List<Int>>,
    highlightErrors: Boolean,
    selectedCell: Pair<Int, Int>?,
    onCellClick: (Int, Int) -> Unit,
    onCellLongClick: ((Int, Int) -> Unit)? = null
) {
    val caveatFontFamily = FontFamily(
        Font(R.font.caveat_semibold, FontWeight.Normal)
    )
    val dsegFontFamily = FontFamily(
        Font(R.font.dseg7_classic_mini_latin_700_italic, FontWeight.Normal)
    )

    val scale = AdaptiveSizes.getScaleFactor()
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val cellSize = (screenWidthDp - 32) / 9
    val noteScale = (cellSize / 40f).coerceIn(0.2f, 2.2f)
    val notePadding = when {
        screenWidthDp < 360 -> (2 * scale).dp
        screenWidthDp < 600 -> (2 * scale).dp
        else -> (8 * scale).dp
    }

    Column(
        modifier = Modifier
            .aspectRatio(1f)
            .border((3 * scale).dp, Color.Black)
    ) {
        for (row in 0 until 9) {
            Row(modifier = Modifier.weight(1f)) {
                for (col in 0 until 9) {
                    val cell = board[row][col]

                    val isError = highlightErrors && !cell.isFixed && cell.value != 0 && cell.value != solution[row][col]
                    val cellBgColor = if (isError) Color(0xFFD0D0D0) else Color.White
                    val textColor = Color.Black

                    val topBorder = when {
                        row == 3 || row == 6 -> (2 * scale).dp
                        else -> (0.5 * scale).dp
                    }

                    val leftBorder = when {
                        col == 3 || col == 6 -> (2 * scale).dp
                        else -> (0.5 * scale).dp
                    }

                    val bottomBorder = if (row == 8) 0.dp else (0.5 * scale).dp
                    val rightBorder = if (col == 8) 0.dp else (0.5 * scale).dp

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(cellBgColor)
                            .combinedClickable(
                                onClick = { onCellClick(row, col) },
                                onLongClick = { onCellLongClick?.invoke(row, col) }
                            )
                            .drawBehind {
                                drawLine(
                                    color = Color.Black,
                                    start = Offset(0f, topBorder.toPx() / 2),
                                    end = Offset(size.width, topBorder.toPx() / 2),
                                    strokeWidth = topBorder.toPx()
                                )
                                drawLine(
                                    color = Color.Black,
                                    start = Offset(leftBorder.toPx() / 2, 0f),
                                    end = Offset(leftBorder.toPx() / 2, size.height),
                                    strokeWidth = leftBorder.toPx()
                                )
                                if (bottomBorder > 0.dp) {
                                    drawLine(
                                        color = Color.Black,
                                        start = Offset(
                                            0f,
                                            size.height - bottomBorder.toPx() / 2
                                        ),
                                        end = Offset(
                                            size.width,
                                            size.height - bottomBorder.toPx() / 2
                                        ),
                                        strokeWidth = bottomBorder.toPx()
                                    )
                                }
                                if (rightBorder > 0.dp) {
                                    drawLine(
                                        color = Color.Black,
                                        start = Offset(size.width - rightBorder.toPx() / 2, 0f),
                                        end = Offset(
                                            size.width - rightBorder.toPx() / 2,
                                            size.height
                                        ),
                                        strokeWidth = rightBorder.toPx()
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (cell.value != 0) {
                            if (cell.isPencil) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(0.dp)
                                    ) {
                                        for (r in 0 until 3) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .weight(1f),
                                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                for (c in 0 until 3) {
                                                    if (r == AppConfig.badgeRow && c == AppConfig.badgeCol) {
                                                        Box(
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .fillMaxHeight()
                                                                .background(Color.White)
                                                                .border((1 * scale).dp, Color.Black),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text(
                                                                text = cell.value.toString(),
                                                                fontSize = (18 * scale).sp,
                                                                color = Color.Black,
                                                                fontWeight = FontWeight.Normal
                                                            )
                                                        }
                                                    } else {
                                                        Box(modifier = Modifier.weight(1f).fillMaxHeight())
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = cell.value.toString(),
                                    fontSize = if (cell.isFixed) (30 * scale).sp else (54 * scale).sp,
                                    color = textColor,
                                    fontWeight = if (cell.isFixed) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = if (cell.isFixed) null else caveatFontFamily
                                )
                            }
                        } else if (cell.notes.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(notePadding),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy((0 * noteScale).dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    for (rowNotes in 0 until 3) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            for (colNotes in 0 until 3) {
                                                val noteNumber = rowNotes * 3 + colNotes + 1
                                                Box(
                                                    modifier = Modifier.weight(1f),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = if (cell.notes.contains(noteNumber)) {
                                                            noteNumber.toString()
                                                        } else {
                                                            ""
                                                        },
                                                        fontSize = (15 * noteScale).sp,
                                                        color = Color.DarkGray,
                                                        textAlign = TextAlign.Center,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = caveatFontFamily,
                                                        modifier = Modifier.wrapContentSize(unbounded = true)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberTimer(
    resetTrigger: Int = 0,
    startOffset: Int = 0,
    isPaused: Boolean = false,
    resetTimer: Int = 0,
    holdDisplay: Boolean = false,
    onTimeUpdate: (Int) -> Unit
): String {
    var elapsedTime by remember(resetTrigger, resetTimer) { mutableIntStateOf(startOffset) }
    var timeWhenPaused by remember(resetTrigger) { mutableIntStateOf(startOffset) }
    // Read inside the ticking loop below via rememberUpdatedState so toggling holdDisplay
    // doesn't restart the LaunchedEffect (that would recompute startTime from the stale
    // startOffset param and jump the timer backward).
    val currentHoldDisplay by rememberUpdatedState(holdDisplay)

    LaunchedEffect(resetTrigger, isPaused, resetTimer, startOffset) {
        if (!isPaused) {
            val startTime = System.currentTimeMillis() - (startOffset * 1000L)
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (!isPaused) {
                    // Always computed from wall clock, tick or no tick, hold or no hold --
                    // this is what guarantees zero drift: releasing the hold always
                    // publishes the true elapsed time, never a value that "lost" the held
                    // ticks.
                    val computed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                    timeWhenPaused = computed
                    if (!currentHoldDisplay) {
                        elapsedTime = computed
                        onTimeUpdate(computed)
                    }
                } else {
                    elapsedTime = timeWhenPaused
                }
            }
        } else {
            elapsedTime = timeWhenPaused
        }
    }

    // Catches the visible display up to the true elapsed time the moment the hold
    // releases, rather than waiting for the next natural tick (up to ~1s later).
    LaunchedEffect(holdDisplay) {
        if (!holdDisplay && !isPaused) {
            elapsedTime = timeWhenPaused
            onTimeUpdate(timeWhenPaused)
        }
    }

    val minutes = elapsedTime / 60
    val seconds = elapsedTime % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
