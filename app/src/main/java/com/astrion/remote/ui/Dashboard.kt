package com.astrion.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.astrion.remote.config.DashboardConfig
import com.astrion.remote.ha.EntityState
import com.astrion.remote.ha.HaClient
import com.astrion.remote.ui.cards.CardRenderer

@Composable
fun DashboardPager(
    config: DashboardConfig,
    entities: Map<String, EntityState>,
    haClient: HaClient,
    pagerState: PagerState
) {
    val pageCount = config.pages.size
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            // Keep neighbors composed so swiping never builds a page mid-gesture
            beyondViewportPageCount = 1
        ) { pageIndex ->
            val page = config.pages[pageIndex]
            val base = Modifier.fillMaxSize()
            Column(
                (if (page.scroll) base.verticalScroll(rememberScrollState()) else base)
                    .padding(vertical = 12.dp)
            ) {
                if (page.showTitle) {
                    Text(
                        page.name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Box(Modifier.size(8.dp))
                }
                page.cards.forEach { card ->
                    // "full_bleed": card spans the full screen width (e.g. the floorplan,
                    // which needs every pixel on this narrow display).
                    val fullBleed = card.options["full_bleed"]?.toString() == "true"
                    Box(Modifier.padding(horizontal = if (fullBleed) 0.dp else 12.dp)) {
                        CardRenderer(card, entities, haClient)
                    }
                    Box(Modifier.size(8.dp))
                }
            }
        }
        PageIndicator(
            pageCount = pageCount,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        )
    }
}

@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        repeat(pageCount) { i ->
            val selected = i == currentPage
            Box(
                Modifier
                    .size(if (selected) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}
