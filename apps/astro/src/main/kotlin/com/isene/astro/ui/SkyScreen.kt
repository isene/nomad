package com.isene.astro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.isene.astro.viewmodel.AstroViewModel
import uniffi.fe2o3_mobile_core.BodyObs
import uniffi.fe2o3_mobile_core.ConditionLevel
import uniffi.fe2o3_mobile_core.DayForecast
import uniffi.fe2o3_mobile_core.Event

@Composable
fun SkyScreen(vm: AstroViewModel, modifier: Modifier = Modifier) {
    val s by vm.sky.collectAsState()
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s.tonight.isNotBlank()) {
            SectionCard {
                Text(s.tonight, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Moon + sun/moon times
        SectionCard {
            s.moon?.let { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.symbol, fontSize = 30.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(m.phaseName, fontWeight = FontWeight.SemiBold)
                        Text("${(m.illumination * 100).toInt()}% illuminated", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.padding(top = 6.dp))
            s.sun?.let { Text("Sun:  ↑ ${it.rise}   ↓ ${it.set}") }
            s.moonRS?.let { Text("Moon: ↑ ${it.rise}   ↓ ${it.set}") }
        }

        // Ephemeris table
        SectionHeader("Ephemeris")
        SectionCard {
            EphemHeaderRow()
            s.bodies.forEach { b -> EphemRow(b, vm.isUpNow(b)) }
            if (s.bodies.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
        }

        // Visible planets
        if (s.planets.isNotEmpty()) {
            SectionHeader("Visible planets")
            SectionCard {
                s.planets.forEach { p ->
                    Row {
                        Text(p.symbol, modifier = Modifier.width(28.dp))
                        Text(p.name, modifier = Modifier.width(96.dp))
                        Text("↑ ${p.rise}  ↓ ${p.set}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Weather + conditions
        SectionHeader("Weather & observing conditions")
        SectionCard {
            if (s.weather.isEmpty()) {
                Text(if (s.loadingNet) "Loading…" else "No forecast", style = MaterialTheme.typography.bodySmall)
            } else {
                s.weather.take(9).forEach { d -> WeatherRow(d, vm.conditionLevelFor(d)) }
            }
        }

        // Events
        SectionHeader("Upcoming events")
        SectionCard {
            if (s.events.isEmpty()) {
                Text(if (s.loadingNet) "Loading…" else "No events", style = MaterialTheme.typography.bodySmall)
            } else {
                s.events.take(12).forEach { e -> EventRow(e) }
            }
        }

        // APOD
        s.apodUrl?.let { url ->
            SectionHeader("Astronomy Picture of the Day")
            AsyncImage(
                model = url,
                contentDescription = "APOD",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
            )
        }

        // Starchart
        s.starchartUrl?.let { url ->
            SectionHeader("Tonight's sky")
            AsyncImage(
                model = url,
                contentDescription = "Starchart",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
            )
        }

        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}

@Composable
private fun EphemHeaderRow() {
    Row {
        cell("Body", 96.dp, header = true)
        cell("Rise", 64.dp, header = true)
        cell("Trans", 64.dp, header = true)
        cell("Set", 64.dp, header = true)
    }
}

@Composable
private fun EphemRow(b: BodyObs, up: Boolean) {
    val color = if (up) Color(0xFF7CFF9B) else MaterialTheme.colorScheme.onSurface
    Row {
        Text(
            b.display,
            modifier = Modifier.width(96.dp),
            color = color,
            fontWeight = if (up) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp,
        )
        monoCell(b.rise, 64.dp, color)
        monoCell(b.transit, 64.dp, color)
        monoCell(b.set, 64.dp, color)
    }
}

@Composable
private fun WeatherRow(d: DayForecast, level: ConditionLevel) {
    val (c, label) = when (level) {
        ConditionLevel.GOOD -> Color(0xFF7CFF9B) to "good"
        ConditionLevel.FAIR -> Color(0xFFFFE08A) to "fair"
        ConditionLevel.POOR -> Color(0xFFFF8A80) to "poor"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.width(12.dp)) {
            Surface(color = c, shape = CircleShape, modifier = Modifier.width(10.dp)) { Text(" ") }
        }
        Spacer(Modifier.width(8.dp))
        Text(d.date.substring(5), modifier = Modifier.width(56.dp), fontSize = 13.sp)
        Text(d.symbol, modifier = Modifier.width(28.dp))
        Text("${d.cloud.toInt()}%☁", modifier = Modifier.width(56.dp), fontSize = 12.sp)
        Text("${d.tempLow.toInt()}/${d.tempHigh.toInt()}°", modifier = Modifier.width(56.dp), fontSize = 12.sp)
        Text("${d.wind.toInt()}m/s", modifier = Modifier.width(52.dp), fontSize = 12.sp)
        Text(label, color = c, fontSize = 12.sp)
    }
}

@Composable
private fun EventRow(e: Event) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row {
            Text(e.date.substring(5), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, modifier = Modifier.width(52.dp))
            if (e.time.isNotBlank()) Text(e.time.take(5), fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        }
        Text(e.event, fontSize = 13.sp)
    }
}

@Composable
private fun cell(text: String, w: androidx.compose.ui.unit.Dp, header: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(w),
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 12.sp,
        color = if (header) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun monoCell(text: String, w: androidx.compose.ui.unit.Dp, color: Color) {
    Text(text, modifier = Modifier.width(w), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color, textAlign = TextAlign.Start)
}
