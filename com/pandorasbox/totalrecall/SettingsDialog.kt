package com.pandorasbox.totalrecall

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun SettingsDialog(
    themePreferenceManager: ThemePreferenceManager,
    indexedCount: Int,
    onReindexClicked: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTheme by remember { mutableStateOf(themePreferenceManager.themeMode) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Appearance Theme Options
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = (selectedTheme == AppThemeMode.SYSTEM),
                            onClick = {
                                selectedTheme = AppThemeMode.SYSTEM
                                themePreferenceManager.themeMode = AppThemeMode.SYSTEM
                            }
                        )
                        Text("System Default")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = (selectedTheme == AppThemeMode.LIGHT),
                            onClick = {
                                selectedTheme = AppThemeMode.LIGHT
                                themePreferenceManager.themeMode = AppThemeMode.LIGHT
                            }
                        )
                        Text("Light Theme")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = (selectedTheme == AppThemeMode.DARK),
                            onClick = {
                                selectedTheme = AppThemeMode.DARK
                                themePreferenceManager.themeMode = AppThemeMode.DARK
                            }
                        )
                        Text("Dark Theme")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Index Stats
                Text(
                    text = "Index Information",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Indexed Photos: $indexedCount",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Reindex Button
                OutlinedButton(
                    onClick = {
                        onReindexClicked()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reindex Complete Gallery")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = onDismiss) {
                        Text("Done")
                    }
                }
            }
        }
    }
}
