package com.rokid.glass.speech.privateservice

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class PrivateSpeechPagerAdapter(activity: PrivateSpeechActivity) : FragmentStateAdapter(activity) {
    val fragments: List<Fragment> = listOf(
        PrivateSpeechInitFragment(),
        PrivateSpeechAsrFragment(),
        PrivateSpeechTtsFragment(),
    )

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment = fragments[position]
}
