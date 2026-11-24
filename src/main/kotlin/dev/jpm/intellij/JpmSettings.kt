package dev.jpm.intellij

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.bindItem
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

    /** Keep a run configuration for every main entry and script. */
    var runConfigurations by property(true)

    /** Run `jpm format` on Java files when they are saved. */
    var formatOnSave by property(false)

    /** Install jpm upgrades and global extension updates found when the IDE starts, instead of offering them. */
    var autoUpdate by property(false)

    /** Take jpm's prereleases too (`jpm upgrade --canary`). */
    var canary by property(false)
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
            row {
                checkBox("Make a run configuration for every main entry and script")
                    .bindSelected({ state.runConfigurations }, { state.runConfigurations = it })
            }
            row {
                checkBox("Run jpm format on Java files when they are saved")
                    .bindSelected({ state.formatOnSave }, { state.formatOnSave = it })
                    .comment("Needs jpm-format: jpm add -g github:java-package-manager/jpm-format")
            }
            group("Updates") {
                row {
                    checkBox("Install jpm upgrades and global extension updates by themselves")
                        .bindSelected({ state.autoUpdate }, { state.autoUpdate = it })
                        .comment("When the IDE starts, the plugin checks for them; when this is off, it offers them in a notification")
                }
                row("Release channel:") {
                    comboBox(listOf(false, true), SimpleListCellRenderer.create("") { if (it) "Canary (prereleases)" else "Stable" })
                        .bindItem({ state.canary }, { state.canary = it ?: false })
                        .comment("Canary takes jpm's prereleases too, like jpm upgrade --canary")
                }
            }
        }
    }

    override fun apply() {
        val before = JpmSettings.get().state.jpmPath
        super.apply()
        if (JpmSettings.get().state.jpmPath != before) {
            JpmSchemaProvider.forget()
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread { JpmSchemaProvider.update() }
        }
    }
}
