package com.smartmanager.whatsappshoot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartmanager.whatsappshoot.R

@Composable
fun PrivacyScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle(stringResource(R.string.p_title), stringResource(R.string.p_subtitle))

        SectionCard(stringResource(R.string.p_c1_title)) {
            Bullet(stringResource(R.string.p_c1_b1))
            Bullet(stringResource(R.string.p_c1_b2))
        }
        SectionCard(stringResource(R.string.p_c2_title)) {
            Bullet(stringResource(R.string.p_c2_b1))
            Bullet(stringResource(R.string.p_c2_b2))
            Bullet(stringResource(R.string.p_c2_b3))
            Bullet(stringResource(R.string.p_c2_b4))
        }
        SectionCard(stringResource(R.string.p_c3_title)) {
            Bullet(stringResource(R.string.p_c3_b1))
            Bullet(stringResource(R.string.p_c3_b2))
            Bullet(stringResource(R.string.p_c3_b3))
        }
        SectionCard(stringResource(R.string.p_c4_title)) {
            Bullet(stringResource(R.string.p_c4_b1))
            Bullet(stringResource(R.string.p_c4_b2))
            Bullet(stringResource(R.string.p_c4_b3))
            Bullet(stringResource(R.string.p_c4_b4))
        }
    }
}

@Composable
private fun Bullet(text: String) = Text("•  $text", style = MaterialTheme.typography.bodyMedium)
