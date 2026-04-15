package com.rokid.phone.ui.classicbt.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.RawResourceDataSource
import com.rokid.phone.R
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.databinding.ActivityBluetoothNofindBinding
import com.rokid.phone.ui.MainPhoneActivity


class BluetoothNoFindActivity : BaseActivity<ActivityBluetoothNofindBinding>() {

    var mTutorialPlayer: ExoPlayer? = null

    override fun onInit(savedInstanceState: Bundle?) {
        playTutorialVideo(binding.tutorialVideo, R.raw.record)

        binding.tvSkip.setOnClickListener {
            startActivity(Intent(this, MainPhoneActivity::class.java))
            finish()
        }

        binding.ivBack.setOnClickListener {
            finish()
        }
    }

    override fun initViewBinding(): ActivityBluetoothNofindBinding {
        return ActivityBluetoothNofindBinding.inflate(layoutInflater)
    }



    fun playTutorialVideo(playerView: PlayerView, videoResId: Int) {
        this?.let {
            val uri = RawResourceDataSource.buildRawResourceUri(videoResId)
            val dataSourceFactory = DefaultDataSource.Factory(it)
            mTutorialPlayer = ExoPlayer.Builder(it).build().also { player ->
                playerView.player = player
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
                player.setMediaSource(mediaSource)
                player.prepare()
                player.playWhenReady = true

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            player.pause()
                            player.seekTo(player.duration)
                        }
                    }
                })
            }

            // 监听播放按钮点击
            val playButton =  playerView.findViewById<View>(com.google.android.exoplayer2.R.id.exo_play)
            playButton?.setOnClickListener {
                mTutorialPlayer?.seekTo(0)// 回到开头
                mTutorialPlayer?.playWhenReady = true
            }
        }
    }

}