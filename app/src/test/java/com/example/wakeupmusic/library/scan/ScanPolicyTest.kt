package com.example.wakeupmusic.library.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPolicyTest {

    private val music = "/storage/emulated/0/Music"

    @Test
    fun shortClipsAreSkipped() {
        assertFalse(ScanPolicy().accepts("$music/note.mp3", 5_000, false))
    }

    @Test
    fun clipAtTheMinimumIsKept() {
        assertTrue(ScanPolicy().accepts("$music/song.mp3", 30_000, false))
    }

    @Test
    fun unknownDurationIsKept() {
        assertTrue(ScanPolicy().accepts("$music/song.mp3", 0, false))
    }

    @Test
    fun ringtoneAlarmAndNotificationFoldersAreSkipped() {
        val policy = ScanPolicy()
        assertFalse(policy.accepts("/storage/emulated/0/Ringtones/x.mp3", 120_000, false))
        assertFalse(policy.accepts("/storage/emulated/0/Alarms/x.mp3", 120_000, false))
        assertFalse(policy.accepts("/storage/emulated/0/Notifications/x.mp3", 120_000, false))
    }

    @Test
    fun systemFlagSkipsEvenOutsideSystemFolders() {
        assertFalse(ScanPolicy().accepts("$music/x.mp3", 120_000, true))
    }

    @Test
    fun blacklistRemovesTheWholeSubtree() {
        val policy = ScanPolicy(blacklist = listOf("$music/Podcasts"))
        assertFalse(policy.accepts("$music/Podcasts/ep1.mp3", 600_000, false))
        assertFalse(policy.accepts("$music/Podcasts/deep/ep2.mp3", 600_000, false))
        assertTrue(policy.accepts("$music/Songs/a.mp3", 200_000, false))
    }

    @Test
    fun whitelistLimitsTheLibraryToListedFolders() {
        val policy = ScanPolicy(whitelist = listOf("$music/Songs"))
        assertTrue(policy.accepts("$music/Songs/a.mp3", 200_000, false))
        assertFalse(policy.accepts("$music/Other/b.mp3", 200_000, false))
    }

    @Test
    fun folderNamesMatchOnWholeSegmentsOnly() {
        val policy = ScanPolicy(blacklist = listOf("$music/Song"))
        assertTrue(policy.accepts("$music/Songs/a.mp3", 200_000, false))
    }

    @Test
    fun trailingSlashInRuleIsIgnored() {
        assertFalse(ScanPolicy(blacklist = listOf("$music/Podcasts/")).accepts("$music/Podcasts/ep.mp3", 600_000, false))
    }
}
