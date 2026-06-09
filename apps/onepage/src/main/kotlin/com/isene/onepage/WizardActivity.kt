package com.isene.onepage

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * First-run permission walkthrough. The whole point: after this wizard the
 * launcher needs ZERO trawling through Android settings. Every special grant
 * OnePage will ever need is requested here, in order, each verified on return
 * (auto-advance when granted):
 *
 *   1. Default home launcher        (RoleManager ROLE_HOME, verified)
 *   2. Display over other apps      (the ColorOS Home-button fix, verified)
 *   3. Battery optimization exempt  (anti phantom-kill, verified)
 *   4. ColorOS auto-start           (vendor setting, no API — self-attest)
 *
 * There are NO runtime permission dialogs after this: every other permission
 * is install-time (auto-granted). The only later prompt Android itself
 * insists on is the one-time per-widget bind consent when adding a widget —
 * the final step tells the user to expect it.
 *
 * Re-run any time: Edit mode → Setup.
 */
class WizardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WizardScreen(onDone = {
                        Prefs.setSetupDone(this, true)
                        startActivity(Intent(this, HomeActivity::class.java))
                        finish()
                    })
                }
            }
        }
    }
}

private fun isDefaultHome(c: Context): Boolean =
    (c.getSystemService(Context.ROLE_SERVICE) as RoleManager).isRoleHeld(RoleManager.ROLE_HOME)

private fun hasOverlay(c: Context): Boolean = Settings.canDrawOverlays(c)

private fun ignoresBattery(c: Context): Boolean =
    (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(c.packageName)

@Composable
private fun WizardScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) } // bump to re-evaluate checks

    // Re-check grant state every time the wizard regains focus (returning
    // from a Settings page), and auto-advance through satisfied steps.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { tick++ }

    // Evaluate the verifiable checks fresh on every tick/step change.
    val checks: Map<Int, Boolean> = remember(tick, step) {
        mapOf(
            1 to isDefaultHome(ctx),
            2 to hasOverlay(ctx),
            3 to ignoresBattery(ctx),
        )
    }
    // Auto-advance: skip past any verifiable step that's already granted.
    if (step in 1..3 && checks[step] == true) {
        step++
        return
    }

    val pkgUri = Uri.parse("package:" + ctx.packageName)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        when (step) {
            0 -> StepCard(
                step = 0,
                title = "OnePage",
                body = "One screen. Your widgets, placed freely. Nothing else.\n\n" +
                    "This short setup grants everything OnePage needs, once. " +
                    "After it, there is nothing to dig out of Android settings.",
                actionLabel = null,
                granted = null,
                onAction = {},
                onNext = { step = 1 },
                onSkip = null,
            )
            1 -> StepCard(
                step = 1,
                title = "Default home launcher",
                body = "Make OnePage the home app so the Home button lands here.",
                actionLabel = "Set as default",
                granted = checks[1],
                onAction = {
                    val rm = ctx.getSystemService(Context.ROLE_SERVICE) as RoleManager
                    try {
                        roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    } catch (_: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                    }
                },
                onNext = { step = 2 },
                onSkip = { step = 2 },
            )
            2 -> StepCard(
                step = 2,
                title = "Display over other apps",
                body = "The important one on ColorOS: without this, the Home " +
                    "button can't bring the launcher back over a fullscreen " +
                    "app. Find OnePage in the list and allow it.",
                actionLabel = "Open the setting",
                granted = checks[2],
                onAction = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
                    )
                },
                onNext = { step = 3 },
                onSkip = { step = 3 },
            )
            3 -> StepCard(
                step = 3,
                title = "Battery optimization",
                body = "Exempt OnePage from battery optimization so ColorOS " +
                    "doesn't kill the launcher in the background. OnePage " +
                    "does no background work; the exemption just keeps it alive.",
                actionLabel = "Allow",
                granted = checks[3],
                onAction = {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            pkgUri,
                        ),
                    )
                },
                onNext = { step = 4 },
                onSkip = { step = 4 },
            )
            4 -> StepCard(
                step = 4,
                title = "ColorOS auto-start",
                body = "Last one, and Android has no API for it, so it's " +
                    "manual:\n\nSettings → Battery → Battery usage → OnePage → " +
                    "Allow background activity + Allow auto-launch.\n\n" +
                    "The button below jumps to OnePage's app info as a " +
                    "shortcut.\n\nHeads-up for later: the first time you add " +
                    "any widget, Android shows a one-time \"allow widget\" " +
                    "consent — that's the system, not a missing setting.",
                actionLabel = "Open app info",
                granted = null,
                onAction = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
                    )
                },
                onNext = { step = 5 },
                onSkip = null,
            )
            else -> {
                val missing = buildList {
                    if (!isDefaultHome(ctx)) add("not the default launcher")
                    if (!hasOverlay(ctx)) add("no overlay permission (Home button may not work!)")
                    if (!ignoresBattery(ctx)) add("still battery-optimized")
                }
                StepCard(
                    step = 5,
                    title = if (missing.isEmpty()) "All set" else "Almost there",
                    body = if (missing.isEmpty()) {
                        "Everything is granted. Long-press the empty home " +
                            "screen to enter edit mode and add widgets."
                    } else {
                        "Granted with gaps: " + missing.joinToString("; ") +
                            ".\n\nYou can go back and fix these, or finish " +
                            "anyway and re-run setup later (Edit mode → Setup)."
                    },
                    actionLabel = if (missing.isEmpty()) null else "Go back",
                    granted = null,
                    onAction = { step = 1 },
                    onNext = onDone,
                    onSkip = null,
                    nextLabel = "Finish",
                )
            }
        }
    }
}

@Composable
private fun StepCard(
    step: Int,
    title: String,
    body: String,
    actionLabel: String?,
    granted: Boolean?,
    onAction: () -> Unit,
    onNext: () -> Unit,
    onSkip: (() -> Unit)?,
    nextLabel: String = "Next",
) {
    Text(
        text = if (step in 1..4) "Step $step of 4" else "",
        style = MaterialTheme.typography.labelMedium,
    )
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(16.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(24.dp))
    if (granted == true) {
        Text("✓ Granted", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
    }
    if (actionLabel != null && granted != true) {
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
            Text(actionLabel)
        }
        Spacer(Modifier.height(12.dp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (onSkip != null) {
            TextButton(onClick = onSkip) { Text("Skip") }
        } else {
            Spacer(Modifier.height(1.dp))
        }
        // The verifiable steps advance themselves on grant; Next is the
        // manual path (e.g. user granted but the check can't see it yet).
        TextButton(onClick = onNext) { Text(nextLabel) }
    }
}
