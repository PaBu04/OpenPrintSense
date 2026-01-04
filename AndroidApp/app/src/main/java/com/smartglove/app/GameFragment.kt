package com.smartglove.app

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.smartglove.app.databinding.FragmentGameBinding
import java.util.Random

class GameFragment : Fragment(), GloveDataListener {

    private var _binding: FragmentGameBinding? = null
    private val binding get() = _binding!!

    private lateinit var mainActivity: MainActivity

    private var gameRunning = false
    private var score = 0
    private var highscore = 0
    private val gameHandler = Handler(Looper.getMainLooper())
    private val random = Random()

    private val comets = mutableListOf<ImageView>()
    private val stars = mutableListOf<ImageView>()
    
    private var smoothedStretchValue = 0f
    private val smoothingFactor = 0.2f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGameBinding.inflate(inflater, container, false)
        mainActivity = activity as MainActivity
        
        binding.btnStartGame.setOnClickListener {
            if (gameRunning) {
                stopGame()
            } else {
                startGame()
            }
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        mainActivity.addGloveDataListener(this)
        loadHighscore()
        updateLanguage()
    }

    override fun onPause() {
        super.onPause()
        mainActivity.removeGloveDataListener(this)
        if (gameRunning) {
            stopGame()
        }
    }

    private fun startGame() {
        if (!mainActivity.isCalibrated) {
            Toast.makeText(requireContext(), mainActivity.getString("not_calibrated"), Toast.LENGTH_SHORT).show()
            return
        }

        binding.gameContainer.post {
            if (view == null) return@post

            gameRunning = true
            score = 0
            smoothedStretchValue = 50f
            updateScoreDisplay()
            binding.btnStartGame.text = mainActivity.getString("stop_game")

            binding.ivSpaceship.y = binding.gameContainer.height - binding.ivSpaceship.height - 20f

            comets.forEach { binding.gameContainer.removeView(it) }
            comets.clear()
            stars.forEach { binding.gameContainer.removeView(it) }
            stars.clear()

            gameHandler.post(gameLoop)
        }
    }

    private fun stopGame() {
        gameRunning = false
        binding.btnStartGame.text = mainActivity.getString("start_game")
        gameHandler.removeCallbacks(gameLoop)

        if (score > highscore) {
            highscore = score
            saveHighscore()
            updateScoreDisplay()
        }

        binding.gameContainer.post {
            if (view == null) return@post
            comets.forEach { binding.gameContainer.removeView(it) }
            comets.clear()
            stars.forEach { binding.gameContainer.removeView(it) }
            stars.clear()
        }
    }

    private val gameLoop = object : Runnable {
        override fun run() {
            if (!gameRunning) return

            if (random.nextInt(100) < 7) { 
                spawnComet()
            }
            if (random.nextInt(100) < 5) {
                spawnStar()
            }

            moveGameObjects()

            gameHandler.postDelayed(this, 30)
        }
    }

    private fun spawnComet() {
        if (binding.gameContainer.width == 0) return
        val comet = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_comet)
            layoutParams = ViewGroup.LayoutParams(80, 80)
            y = -80f
            x = random.nextInt(binding.gameContainer.width - 80).toFloat()
        }
        binding.gameContainer.addView(comet)
        comets.add(comet)
    }

    private fun spawnStar() {
        if (binding.gameContainer.width == 0) return
        val star = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_star)
            layoutParams = ViewGroup.LayoutParams(50, 50)
            y = -50f
            x = random.nextInt(binding.gameContainer.width - 50).toFloat()
        }
        binding.gameContainer.addView(star)
        stars.add(star)
    }

    private fun moveGameObjects() {
        val hitboxPadding = 20 // Reduce hitbox by 20 pixels on each side
        val spaceshipRect = Rect(
            (binding.ivSpaceship.x + hitboxPadding).toInt(), 
            (binding.ivSpaceship.y + hitboxPadding).toInt(), 
            (binding.ivSpaceship.x + binding.ivSpaceship.width - hitboxPadding).toInt(), 
            (binding.ivSpaceship.y + binding.ivSpaceship.height - hitboxPadding).toInt()
        )

        val cometsToRemove = comets.filter { comet ->
            comet.y += 15
            if (comet.y > binding.gameContainer.height) {
                binding.gameContainer.removeView(comet)
                return@filter true
            }
            val cometRect = Rect(comet.x.toInt(), comet.y.toInt(), (comet.x + comet.width).toInt(), (comet.y + comet.height).toInt())
            if (Rect.intersects(spaceshipRect, cometRect)) {
                stopGame()
                Toast.makeText(requireContext(), "Game Over!", Toast.LENGTH_SHORT).show()
            }
            false
        }
        comets.removeAll(cometsToRemove)

        val starsToRemove = stars.filter { star ->
            star.y += 10
            if (star.y > binding.gameContainer.height) {
                binding.gameContainer.removeView(star)
                return@filter true
            }
            val starRect = Rect(star.x.toInt(), star.y.toInt(), (star.x + star.width).toInt(), (star.y + star.height).toInt())
            if (Rect.intersects(spaceshipRect, starRect)) {
                score++
                updateScoreDisplay()
                binding.gameContainer.removeView(star)
                return@filter true
            }
            false
        }
        stars.removeAll(starsToRemove)
    }

    override fun onStretchValueReceived(value: Int) {
        if (gameRunning) {
            smoothedStretchValue = (smoothingFactor * value) + ((1 - smoothingFactor) * smoothedStretchValue)

            val gameWidth = binding.gameContainer.width
            val spaceshipWidth = binding.ivSpaceship.width
            if (gameWidth > 0) {
                binding.ivSpaceship.x = (smoothedStretchValue / 100f) * (gameWidth - spaceshipWidth)
            }
        }
    }

    private fun loadHighscore() {
        highscore = mainActivity.prefs.getInt(MainActivity.PREF_HIGHSCORE, 0)
        updateScoreDisplay()
    }

    private fun saveHighscore() {
        mainActivity.prefs.edit().putInt(MainActivity.PREF_HIGHSCORE, highscore).apply()
    }

    private fun updateScoreDisplay() {
        binding.tvScore.text = "${mainActivity.getString("score")}: $score | ${mainActivity.getString("highscore")}: $highscore"
    }
    
    override fun onLanguageChanged() {
        updateLanguage()
    }
    
    private fun updateLanguage(){
        binding.tvGameTitle.text = mainActivity.getString("game_title")
        binding.tvGameDescription.text = mainActivity.getString("game_description")
        binding.btnStartGame.text = mainActivity.getString(if (gameRunning) "stop_game" else "start_game")
        updateScoreDisplay()
    }

    // Unused listener methods
    override fun onConnectionStateUpdate(isConnected: Boolean) {}
    override fun onStatusUpdate(status: String) {}
    override fun onDeviceIdRead(deviceId: String) {}
    override fun onRawValueReceived(value: Int) {}

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
