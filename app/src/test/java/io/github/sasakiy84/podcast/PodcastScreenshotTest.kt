package io.github.sasakiy84.podcast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Navigates the real Compose screens with local sample data; no network or audio service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PodcastScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val directory = File(requireNotNull(System.getProperty("podcast.screenshotDir")), "all-screens")
    private val titles = listOf("MORNING\nNOTES", "TECH\nTABLE", "WORLD\nNEWS", "DEEP\nBREATH", "ENGLISH", "WEEKLY", "DESIGN", "SCIENCE", "DAILY")
    private val shows = titles.mapIndexed { i, title ->
        Podcast("https://example.org/feed/$i", listOf("朝のノート", "技術のテーブル", "世界のニュース", "深呼吸ラジオ", "English Daily", "今週の話", "デザインの時間", "科学の窓", "日々のこと")[i], "音の便り", artwork(title, i), "日々の小さな発見を、ゆっくり話す番組です。毎週、新しいエピソードをお届けします。", website = "https://example.org/show/$i")
    }
    private val episodes = listOf("静かな朝のつづき", "小さな習慣を見つける", "技術と暮らしの接点", "世界のニュースを振り返る", "週末のコーヒー", "言葉を学ぶ楽しさ").mapIndexed { i, title ->
        Episode("episode-$i", shows[if (i < 2) 0 else i - 1].feedUrl, title, "https://example.org/$i.mp3", "コーヒーを淹れながら、今日の予定を考えます。\n\n今回のテーマは、毎日の暮らしにある小さな発見。最近の出来事や読んだ本についても話しています。", 1_793_491_200_000L - i * 86_400_000L, (30 + i * 7) * 60_000L)
    }
    private val queues = listOf(PlaybackQueue("news", "ニュース", episodes.filterIndexed { i, _ -> i != 1 }.take(3).map { it.id }), PlaybackQueue("tech", "技術", listOf(episodes[2].id)), PlaybackQueue("english", "英語学習"))
    private val library = Library(shows, episodes,
        progress = mapOf(episodes[0].id to ListeningProgress(120_000, episodes[0].durationMs), episodes[1].id to ListeningProgress(episodes[1].durationMs, episodes[1].durationMs, true)),
        downloads = mapOf(episodes[0].id to EpisodeDownload(1, DownloadStatus.Complete, 100), episodes[2].id to EpisodeDownload(2, DownloadStatus.Running, 42)),
        queues = queues, queueRules = listOf(QueueRule("news", shows[0].feedUrl, "朝,暮らし", "広告", autoDownload = true)), autoDownloadFeeds = setOf(shows[0].feedUrl))
    private val base = PodcastUiState(library = library, playback = PlaybackUi(episodes[0].id, true, positionMs = 120_000, durationMs = episodes[0].durationMs, connected = true))
    private val ui = mutableStateOf(base)

    @Test fun captureLightScreens(): Unit = captureAll("light")

    @Test
    @Config(qualifiers = "ja-w360dp-h800dp-night")
    fun captureDarkScreens(): Unit = captureAll("dark")

    private fun captureAll(theme: String): Unit {
        compose.setContent { PodcastApp(ui.value) }
        fun shot(name: String): Unit = screenshot(theme, name)
        fun tab(name: String): Unit { compose.onNodeWithContentDescription("タブ: $name").performClick() }
        fun text(label: String): Unit { compose.onNodeWithText(label).performClick() }
        fun icon(label: String): Unit { compose.onNodeWithContentDescription(label).performClick() }
        fun replace(value: PodcastUiState): Unit { compose.runOnIdle { ui.value = value }; compose.waitForIdle() }

        shot("01-library")
        replace(base.copy(podcastSort = PodcastSort.Updated))
        shot("43-updated-order")
        replace(base.copy(refreshing = true, refreshCompleted = 4, refreshTotal = 9))
        shot("44-refresh-progress")
        replace(base)
        icon("番組一覧のメニュー")
        shot("37-opml-menu")
        text("OPML をインポート")
        icon(shows[0].title)
        shot("02-podcast-details")
        text("購読元")
        shot("38-podcast-source")
        icon("購読元を閉じる")
        text("再生速度 1.0×")
        shot("39-podcast-speed")
        text("完了")
        icon("購読を解除")
        shot("03-unsubscribe-confirmation")
        text("キャンセル")
        icon("一覧に戻る")

        tab("エピソード")
        shot("04-episodes")
        text("未再生")
        shot("05-unplayed")
        text("ダウンロード")
        shot("06-downloads")
        replace(base.copy(library = library.copy(favorites = setOf(episodes[0].id, episodes[2].id))))
        compose.onNodeWithText("お気に入り").performScrollTo().performClick()
        shot("40-favorites")
        compose.onNodeWithText("ダウンロード").performScrollTo().performClick()
        replace(base)
        compose.onNodeWithContentDescription("エピソード詳細: ${episodes[0].title}").performTouchInput { swipeLeft() }
        shot("41-swipe-left")
        compose.onNodeWithContentDescription("エピソード詳細: ${episodes[2].title}").performTouchInput { swipeRight() }
        shot("42-swipe-right")
        icon("キューに追加: ${episodes[2].title}")
        text("完了")
        icon("エピソード詳細: ${episodes[0].title}")
        shot("07-episode-details")
        text("キューに追加・編集")
        shot("08-queue-membership")
        text("完了")

        tab("キュー")
        shot("09-queues")
        text("キューを作成")
        shot("10-create-queue")
        text("キャンセル")
        text("ニュース")
        shot("11-queue-details")
        icon("キュー名を変更")
        shot("12-rename-queue")
        text("キャンセル")
        icon("キューを削除")
        shot("13-delete-queue-confirmation")
        text("キャンセル")
        icon("キュー内の操作: ${episodes[0].title}")
        shot("14-queue-episode-menu")
        text("下へ移動")
        text("自動追加")
        shot("15-queue-automatic-addition")
        icon("自動追加ルールを編集")
        shot("16-edit-rule")
        text("キャンセル")
        compose.onNodeWithText("番組の自動追加を設定").performScrollTo().performClick()
        shot("17-new-rule")
        compose.onNode(hasText(shows[0].title) and hasAnyAncestor(isDialog())).performClick()
        shot("18-rule-podcast-picker")
        text(shows[1].title)
        text("キャンセル")
        icon("キュー一覧に戻る")
        text("英語学習")
        shot("19-empty-queue-details")

        tab("ポッドキャスト")
        icon("プレーヤーを開く")
        compose.onNodeWithContentDescription("再生位置").assertIsDisplayed()
        compose.onNodeWithContentDescription("ダウンロード").assertDoesNotExist()
        shot("20-player")
        icon("再生速度 1.0×")
        shot("21-playback-speed")
        text("1.5×")
        icon("その他の操作")
        shot("22-player-menu")
        text("再生済みにする")
        text("詳細")
        shot("23-player-details")
        text("再生中")
        replace(base.copy(playback = base.playback.copy(playing = false)))
        shot("24-player-paused")
        replace(base.copy(playback = base.playback.copy(buffering = true)))
        shot("25-player-buffering")
        icon("プレーヤーを閉じる")
        replace(base)

        tab("検索")
        shot("26-search")
        compose.onNodeWithText("番組名・キーワード").performTextInput("暮らし")
        replace(base.copy(searched = true, results = shows.take(3).map { PodcastCandidate(it.copy(feedUrl = "${it.feedUrl}/found")) }))
        shot("27-search-results")
        text("URLから登録")
        compose.onNodeWithText("番組URL・RSS URL").performTextReplacement("https://open.spotify.com/show/example")
        shot("28-url-input")
        replace(base.copy(searched = true, results = listOf(PodcastCandidate(shows[0].copy(feedUrl = "${shows[0].feedUrl}/found"), "Spotify の番組候補 · Apple Podcasts"))))
        shot("29-url-candidate")
        replace(base.copy(searching = true))
        shot("30-search-loading")
        replace(base.copy(searched = true, searchError = "接続できませんでした。時間をおいて再度お試しください。"))
        shot("31-search-error")
        replace(base.copy(searched = true))
        shot("32-search-no-results")

        replace(PodcastUiState())
        tab("ポッドキャスト")
        shot("33-empty-library")
        tab("エピソード")
        shot("34-empty-episodes")
        text("ダウンロード")
        shot("35-empty-downloads")
        tab("キュー")
        shot("36-empty-queues")
    }

    private fun artwork(title: String, index: Int): String {
        val file = File(directory, "fixtures/$index.png")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(listOf(0xFF123954.toInt(), 0xFF15303F.toInt(), 0xFFAA262C.toInt(), 0xFFE8AD60.toInt(), 0xFF40855E.toInt())[index % 5])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 52f; typeface = Typeface.DEFAULT_BOLD }
        title.split('\n').forEachIndexed { i, line -> canvas.drawText(line, 30f, 150f + i * 62f, paint) }
        paint.alpha = 90
        paint.strokeWidth = 3f
        canvas.drawLine(30f, 320f, 370f, 320f, paint)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return file.toURI().toString()
    }

    private fun screenshot(theme: String, name: String): Unit {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val target = File(directory, "$theme/$name.png")
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Include popup windows as well as dialogs so menus are captured, too.
            for (view in WindowInspector.getGlobalWindowViews()) {
                if (!view.isShown || view.windowVisibility != View.VISIBLE || view.width == 0 || view.height == 0) continue
                val params = view.layoutParams as? WindowManager.LayoutParams
                if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
                }
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                canvas.save()
                canvas.translate(location[0].toFloat(), location[1].toFloat())
                view.draw(canvas)
                canvas.restore()
            }
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
