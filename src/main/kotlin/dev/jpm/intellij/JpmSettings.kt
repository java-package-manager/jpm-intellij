package dev.jpm.intellij

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.panel

class JpmSettingsState : BaseState() {
    /** Path of the jpm executable; empty finds it in ~/.jpm/bin or on PATH. */
    var jpmPath by string("")

    /** Reload the project when project.jsonc or project.lock change. */
    var autoReload by property(true)
}

@State(name = "JpmSettings", storages = [Storage("jpm.xml")])
class JpmSettings : SimplePersistentStateComponent<JpmSettingsState>(JpmSettingsState()) {
    companion object {
        fun get(): JpmSettings = service()
    }
}

class JpmConfigurable : BoundConfigurable("jpm") {
    override fun createPanel(): DialogPanel {
        val state = JpmSettings.get().state
        return panel {
            row("jpm executable:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("jpm Executable"))
                    .bindText({ state.jpmPath ?: "" }, { state.jpmPath = it.trim() })
                    .columns(COLUMNS_LARGE)
                    .comment("Leave empty to use ${Jpm.defaultLocationHint()}")
            }
            row {
                checkBox("Reload the project when project.jsonc or project.lock change")
                    .bindSelected({ state.autoReload }, { state.autoReload = it })
            }
        }
    }

    override fun apply() {
        val before = JpmSettings.get().state.jpmPath
        super.apply()
        if (JpmSettings.get().state.jpmPath != before) {
            // another jpm may have another schema
            JpmSchemaProvider.forget()
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread { JpmSchemaProvider.update() }
        }
    }
}
