package io.github.sasakiy84.podcast

/** A single projection keeps preference callbacks and library updates consistent. */
internal fun PodcastUiState.withPreferences(settings: PlaybackPreferences): PodcastUiState =
    copy(
        defaultQueueId = settings.defaultQueue,
        wifiOnly = settings.wifiOnly,
        normalizeVolume = settings.normalizeVolume,
        louderVolume = settings.louderVolume,
        refreshHours = settings.refreshHours,
        podcastOptions = library.podcasts.associate { it.feedUrl to settings.options(it.feedUrl) },
        podcastSort = settings.sort,
        globalSpeed = settings.globalSpeed,
        podcastSpeeds =
            library.podcasts
                .mapNotNull { podcast ->
                    settings.podcastSpeed(podcast.feedUrl)?.let { podcast.feedUrl to it }
                }
                .toMap(),
    )
