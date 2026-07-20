package io.github.serg987.sudokueinkhtr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mudita.mmd.components.buttons.ButtonMMD


@Composable
fun MainScreen(
    onGameModeSelected: (GameMode, Difficulty, Boolean) -> Unit = { _, _, _ -> },  // ← MODIFICAT: afegit Boolean
    onStatisticsClick: () -> Unit = {},
    onDailySudokuClick: () -> Unit = {},
    onAchievementsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onBackToMainMenu: () -> Unit = {},
    onThemeChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var currentLanguage by remember { mutableStateOf(LanguageManager.loadLanguage(context)) }
    var isDarkTheme by remember { mutableStateOf(ThemeManager.loadDarkMode(context)) }
    val scale = AdaptiveSizes.getScaleFactor()
    var showAlreadyPlayedDialog by remember { mutableStateOf(false) }

    // The E-ink-center per-app refresh mode can change under us at any time. The mode
    // modal (tap on the system bottom bar) slides OVER the activity without an
    // onPause/onResume cycle — the only signal it was open is a window-focus loss/gain —
    // so re-read the mode on every focus regain (which also covers resume-from-background
    // and screensaver wake, since those end in a focus gain too).
    var showRefreshModeWarning by remember { mutableStateOf(EinkOptimizations.isNonNormalRefreshMode()) }
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (focused) showRefreshModeWarning = EinkOptimizations.isNonNormalRefreshMode()
        }
    }

    key(currentLanguage, isDarkTheme) {
        val strings = when (currentLanguage) {
            Language.CATALAN -> StringsCa
            Language.SPANISH -> StringsEs
            Language.ENGLISH -> StringsEn
        }

        var showAlreadyPlayedDialog by remember { mutableStateOf(false) }
        var pendingGameMode by remember { mutableStateOf<Triple<GameMode, Difficulty, Boolean>?>(null) }
        var showResumeDialog by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize()) {
            //  CONTINGUT CENTRAT (títol + botons)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding((16 * scale).dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = strings.appTitle,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = (38 * scale).sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.width((8 * scale).dp))
                        Text(
                            text = "HTR",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = (46 * scale).sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily(
                                    androidx.compose.ui.text.font.Font(io.github.serg987.sudokueinkhtr.R.font.caveat_semibold)
                                ),
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Spacer(modifier = Modifier.height((40 * scale).dp))

                    ButtonMMD(
                        onClick = { 
                            val mode = GameMode.NORMAL
                            val diff = SettingsManager.loadDifficulty(context)
                            val zen = SettingsManager.loadZenMode(context)
                            val savedGame = GameStateManager.loadGame(context, mode, diff, false, zen)
                            if (savedGame != null) {
                                pendingGameMode = Triple(mode, diff, zen)
                                showResumeDialog = true
                            } else {
                                onGameModeSelected(mode, diff, zen)
                            }
                        },
                        modifier = Modifier
                            .height((52 * scale).dp)
                            .fillMaxWidth(0.4f)
                            .border(BorderStroke((2 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp))
                    ) {
                        Text(strings.normalMode, fontSize = (19 * scale).sp)
                    }

                    Spacer(modifier = Modifier.height((20 * scale).dp))

                    ButtonMMD(
                        onClick = { 
                            val mode = GameMode.ATTACK
                            val diff = SettingsManager.loadDifficulty(context)
                            val zen = SettingsManager.loadZenMode(context)
                            val savedGame = GameStateManager.loadGame(context, mode, diff, false, zen)
                            if (savedGame != null) {
                                pendingGameMode = Triple(mode, diff, zen)
                                showResumeDialog = true
                            } else {
                                onGameModeSelected(mode, diff, zen)
                            }
                        },
                        modifier = Modifier
                            .height((52 * scale).dp)
                            .fillMaxWidth(0.4f)
                            .border(BorderStroke((2 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp))
                    ) {
                        Text(strings.attackMode, fontSize = (19 * scale).sp)
                    }

                    Spacer(modifier = Modifier.height((20 * scale).dp))

                    // Botó Daily
                    ButtonMMD(
                        onClick = {
                            if (DailySudokuManager.hasPlayedToday(context)) {
                                showAlreadyPlayedDialog = true
                            } else {
                                onDailySudokuClick()
                            }
                        },
                        modifier = Modifier
                            .height((52 * scale).dp)
                            .fillMaxWidth(0.4f)
                            .border(BorderStroke((2 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp))
                    ) {
                        Text(
                            text = "${strings.dailySudoku}: ${DailySudokuManager.getDailyDifficultyName(strings)}",
                            fontSize = (19 * scale).sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    // Diàleg "Ja has jugat avui"
                    if (showAlreadyPlayedDialog) {
                        AlertDialog(
                            onDismissRequest = { showAlreadyPlayedDialog = false },
                            title = {
                                Text(
                                    text = strings.alreadyPlayedToday,
                                    fontSize = (32 * scale).sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            text = {
                                Column {
                                    Text(
                                        text = "${strings.dailyBestTime}: ${formatTime(DailySudokuManager.getDailyBestTime(context))}",
                                        fontSize = (26 * scale).sp
                                    )
                                    Spacer(modifier = Modifier.height((8 * scale).dp))
                                    Text(
                                        text = "${strings.currentStreak}: ${DailySudokuManager.getCurrentStreak(context)} ${strings.days}",
                                        fontSize = (24 * scale).sp,
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        lineHeight = (30 * scale).sp
                                    )
                                }
                            },
                            confirmButton = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy((4 * scale).dp)
                                ) {
                                    ButtonMMD(
                                        onClick = {
                                            showAlreadyPlayedDialog = false
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height((52 * scale).dp)
                                            .border(BorderStroke((2 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp))
                                    ) {
                                        Text(strings.back, fontSize = (19 * scale).sp)
                                    }
                                }
                            }
                        )
                    }

                    if (showResumeDialog && pendingGameMode != null) {
                        AlertDialog(
                            onDismissRequest = { showResumeDialog = false },
                            modifier = Modifier.border(BorderStroke((3 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp)),
                            shape = RoundedCornerShape((16 * scale).dp),
                            containerColor = Color.White,
                            titleContentColor = Color.Black,
                            textContentColor = Color.Black,
                            title = {
                                Text(
                                    text = strings.resumeGame,
                                    fontSize = (26 * scale).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            },
                            text = {
                                Text(
                                    text = strings.resumeGameMessage,
                                    fontSize = (21 * scale).sp,
                                    color = Color.Black
                                )
                            },
                            confirmButton = {
                                ButtonMMD(
                                    onClick = {
                                        showResumeDialog = false
                                        val (m, d, z) = pendingGameMode!!
                                        onGameModeSelected(m, d, z)
                                    },
                                    modifier = Modifier
                                        .height((52 * scale).dp)
                                        .border(BorderStroke((2 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp))
                                ) {
                                    Text(strings.continue_, fontSize = (19 * scale).sp, color = Color.Black)
                                }
                            },
                            dismissButton = {
                                ButtonMMD(
                                    onClick = {
                                        showResumeDialog = false
                                        val (m, d, z) = pendingGameMode!!
                                        GameStateManager.clearGame(context, m, d, false, z)
                                        onGameModeSelected(m, d, z)
                                    },
                                    modifier = Modifier
                                        .height((52 * scale).dp)
                                        .border(BorderStroke((2 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp))
                                ) {
                                    Text(strings.newGame, fontSize = (19 * scale).sp, color = Color.Black)
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height((20 * scale).dp))

                    ButtonMMD(
                        onClick = onStatisticsClick,
                        modifier = Modifier
                            .height((52 * scale).dp)
                            .fillMaxWidth(0.4f)
                            .border(BorderStroke((2 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp))
                    ) {
                        Text(strings.statistics, fontSize = (19 * scale).sp)
                    }

                    Spacer(modifier = Modifier.height((20 * scale).dp))

                    ButtonMMD(
                        onClick = onAchievementsClick,
                        modifier = Modifier
                            .height((52 * scale).dp)
                            .fillMaxWidth(0.4f)
                            .border(BorderStroke((2 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp))
                    ) {
                        Text(strings.achievements, fontSize = (19 * scale).sp)
                    }

                    Spacer(modifier = Modifier.height((20 * scale).dp))

                    ButtonMMD(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .height((52 * scale).dp)
                            .fillMaxWidth(0.4f)
                            .border(BorderStroke((2 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp))
                    ) {
                        Text(strings.settings, fontSize = (19 * scale).sp)
                    }
                }

                // ✅ Bottom strip: optional refresh-mode warning above the "created by" line
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = (8 * scale).dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (showRefreshModeWarning) {
                        // Styled like the menu buttons for visual consistency, but static —
                        // informational only, no click action.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .border(BorderStroke((2 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp))
                                .padding(horizontal = (16 * scale).dp, vertical = (12 * scale).dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = strings.refreshModeWarning,
                                fontSize = (19 * scale).sp,
                                color = Color.Black,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                        Spacer(modifier = Modifier.height((12 * scale).dp))
                    }
                    Text(
                        text = "Original app by ktacrack. Fork, HTR and UI modifications by serg987.",
                        fontSize = (20 * scale).sp,
                        fontStyle = FontStyle.Italic,
                        color = Color.Black
                    )
                }

        }
    }
}
