package com.jonnyzzz.intellij.hotreload

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Startup activity that ensures the marker service is initialized.
 * The actual marker file creation is handled by [HotReloadMarkerService].
 */
class HotReloadStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Trigger service initialization which creates the marker file
        service<HotReloadMarkerService>()
    }
}
