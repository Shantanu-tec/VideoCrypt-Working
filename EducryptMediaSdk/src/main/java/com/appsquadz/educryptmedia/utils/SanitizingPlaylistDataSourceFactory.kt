package com.appsquadz.educryptmedia.utils

import androidx.media3.datasource.DataSource

class SanitizingPlaylistDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val sanitizer: (String) -> String
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return SanitizingPlaylistDataSource(
            upstreamFactory.createDataSource(),
            sanitizer
        )
    }
}
