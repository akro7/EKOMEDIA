package com.eko.media.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import com.eko.media.R
import com.eko.media.databinding.ActivitySplashBinding

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var b: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Outer glow breathe animation
        val glowPulse = AnimationUtils.loadAnimation(this, R.anim.glow_pulse)
        b.glowOuter.startAnimation(glowPulse)

        // Inner glow with slight delay
        val innerPulse = AnimationUtils.loadAnimation(this, R.anim.glow_pulse)
        innerPulse.startOffset = 200
        b.glowInner.startAnimation(innerPulse)

        // Logo bg circle subtle pulse
        val logoBgPulse = AnimationUtils.loadAnimation(this, R.anim.pulse)
        b.logoBgCircle.startAnimation(logoBgPulse)

        // Logo pulse (stronger)
        val pulse = AnimationUtils.loadAnimation(this, R.anim.pulse)
        b.ivLogo.startAnimation(pulse)

        // Title slide up + fade
        b.tvTitle.alpha = 0f
        b.tvTitle.translationY = 40f
        b.tvTitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(700)
            .setStartDelay(300)
            .start()

        // Accent line expand
        b.accentLine.scaleX = 0f
        b.accentLine.animate()
            .scaleX(1f)
            .setDuration(500)
            .setStartDelay(800)
            .start()

        // Subtitle fade + slide
        b.tvSubtitle.alpha = 0f
        b.tvSubtitle.translationY = 20f
        b.tvSubtitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(900)
            .start()

        // Version fade
        b.tvVersion.alpha = 0f
        b.tvVersion.animate()
            .alpha(0.6f)
            .setDuration(400)
            .setStartDelay(1200)
            .start()

        // Bottom bar slide in
        b.bottomBar.scaleX = 0f
        b.bottomBar.animate()
            .scaleX(1f)
            .setDuration(800)
            .setStartDelay(400)
            .start()

        // Navigate after 2.5s
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            finish()
        }, 2500)
    }
}
