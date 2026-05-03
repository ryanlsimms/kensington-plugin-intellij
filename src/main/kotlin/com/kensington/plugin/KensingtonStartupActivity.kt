package com.kensington.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class KensingtonStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        CdnCssCache.getInstance(project).triggerRefresh()
    }
}
