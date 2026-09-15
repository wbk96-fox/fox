@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.foxtv.app.ui.screens.settings

import com.foxtv.app.ui.theme.FoxTvTheme

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.foxtv.app.BuildConfig
import com.foxtv.app.R
import com.foxtv.app.core.build.AppFeaturePolicy
import com.foxtv.app.ui.components.MemberBrandWordmark

@Composable
fun AboutScreen(
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.about_title),
        subtitle = stringResource(R.string.about_subtitle)
    ) {
        AboutSettingsContent(
            onNavigateToSupportersContributors = onNavigateToSupportersContributors,
            onNavigateToLicensesAttributions = onNavigateToLicensesAttributions
        )
    }
}

@Composable
fun AboutSettingsContent(
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    initialFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.about_title),
            subtitle = stringResource(R.string.about_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = null
        ) {
            val aboutScrollState = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(aboutScrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(modifier = Modifier.height(FoxTvTheme.spacing.xs))

                MemberBrandWordmark(
                    height = 40.dp,
                    contentDescription = stringResource(R.string.cd_foxtv_logo)
                )

                Text(
                    text = stringResource(R.string.about_made_with_love),
                    style = MaterialTheme.typography.labelSmall,
                    color = FoxTvTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = FoxTvTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(FoxTvTheme.spacing.xxs))

                if (AppFeaturePolicy.inAppUpdatesEnabled) {
                    UpdateChannelSettings(initialFocusRequester)
                }

                // The privacy policy is published by whoever operates the configured
                // account backend (LEGAL_PRIVACY_URL in local.properties). Without it
                // there is nothing to open, so the row is omitted rather than shown as
                // a dead action.
                val privacyPolicyUrl = BuildConfig.LEGAL_PRIVACY_URL
                if (privacyPolicyUrl.isNotBlank()) {
                    SettingsActionRow(
                        title = stringResource(R.string.about_privacy_policy),
                        subtitle = stringResource(R.string.about_privacy_policy_subtitle),
                        trailingIcon = Icons.Default.OpenInNew,
                        modifier = if (!AppFeaturePolicy.inAppUpdatesEnabled && initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        },
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(privacyPolicyUrl))
                            context.startActivity(intent)
                        }
                    )
                }

                if (AppFeaturePolicy.supportFoxTvEnabled) {
                    SettingsActionRow(
                        title = stringResource(R.string.support_foxtv_name),
                        subtitle = stringResource(R.string.about_supporters_contributors_subtitle),
                        trailingIcon = Icons.Default.ChevronRight,
                        onClick = onNavigateToSupportersContributors
                    )
                }

                SettingsActionRow(
                    title = stringResource(R.string.about_licenses_attributions),
                    subtitle = stringResource(R.string.about_licenses_attributions_subtitle),
                    trailingIcon = Icons.Default.ChevronRight,
                    onClick = onNavigateToLicensesAttributions
                )
            }
            SettingsVerticalScrollIndicators(state = aboutScrollState)
            }
        }
    }
}
