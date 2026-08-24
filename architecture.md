```mermaid
classDiagram
    direction TB

    namespace app {
        class pkg_app_dev_geode["~"] {
            3 types
        }
        class pkg_app_dev_geode_di["~.di"] {
            3 types
        }
        class pkg_app_dev_geode_ui["~.ui"] {
            117 types
        }
        class pkg_app_dev_geode_ui_theme["~.ui.theme"] {
            16 types
        }
        class pkg_app_dev_geode_editor["~.editor"] {
            98 types
        }
        class pkg_app_dev_geode_export["~.export"] {
            109 types
        }
        class pkg_app_dev_geode_publish["~.publish"] {
            23 types
        }
        class pkg_app_dev_geode_data["~.data"] {
            100 types
        }
        class pkg_app_dev_geode_playback["~.playback"] {
            21 types
        }
        class pkg_app_dev_geode_audio["~.audio"] {
            21 types
        }
        class pkg_app_dev_geode_audio_dsp["~.audio.dsp"] {
            1 types
        }
        class pkg_app_dev_geode_billing["~.billing"] {
            14 types
        }
        class pkg_app_dev_geode_wallpaper["~.wallpaper"] {
            4 types
        }
    }

    namespace scenes {
        class pkg_scenes_dev_geode_audio["~.audio"] {
            1 types
        }
        class pkg_scenes_dev_geode_render["~.render"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_scene["~.render.scene"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_fluid["~.render.fluid"] {
            34 types
        }
        class pkg_scenes_dev_geode_render_compute["~.render.compute"] {
            15 types
        }
        class pkg_scenes_dev_geode_render_offscreen["~.render.offscreen"] {
            6 types
        }
        class pkg_scenes_dev_geode_analysis["~.analysis"] {
            22 types
        }
    }

    namespace gl {
        class pkg_gl_dev_geode_engine_gl["~.engine.gl"] {
            53 types
        }
    }

    namespace audio_core {
        class pkg_audio_core_dev_geode_engine_audio["~.engine.audio"] {
            54 types
        }
    }

    namespace audio_android {
        class pkg_audio_android_dev_geode_engine_audioandroid["~.engine.audioandroid"] {
            9 types
        }
    }

    namespace runtime {
        class pkg_runtime_dev_geode_engine_runtime["~.engine.runtime"] {
            5 types
        }
    }

    namespace build_logic {
        class pkg_build_logic_root["(build-logic)"] {
            11 types
        }
    }

    pkg_app_dev_geode_ui --> pkg_app_dev_geode_data : 26
    pkg_app_dev_geode_ui --> pkg_scenes_dev_geode_render_scene : 14
    pkg_scenes_dev_geode_render --> pkg_scenes_dev_geode_render_scene : 13
    pkg_app_dev_geode_ui --> pkg_scenes_dev_geode_analysis : 11
    pkg_app_dev_geode_ui --> pkg_app_dev_geode_audio : 9
    pkg_scenes_dev_geode_render_scene --> pkg_scenes_dev_geode_render : 8
    pkg_scenes_dev_geode_render_scene --> pkg_scenes_dev_geode_render_fluid : 8
    pkg_app_dev_geode_ui --> pkg_app_dev_geode_ui_theme : 8
    pkg_app_dev_geode_ui --> pkg_scenes_dev_geode_render : 8
    pkg_scenes_dev_geode_render_fluid --> pkg_scenes_dev_geode_render_scene : 6
    pkg_app_dev_geode_data --> pkg_scenes_dev_geode_render_scene : 6
    pkg_app_dev_geode_export --> pkg_scenes_dev_geode_render : 6
    pkg_scenes_dev_geode_render_compute --> pkg_gl_dev_geode_engine_gl : 6
    pkg_scenes_dev_geode_render_scene --> pkg_scenes_dev_geode_analysis : 5
    pkg_app_dev_geode_data --> pkg_app_dev_geode_export : 4
    pkg_scenes_dev_geode_render_fluid --> pkg_scenes_dev_geode_analysis : 4
    pkg_app_dev_geode_audio_dsp --> pkg_audio_android_dev_geode_engine_audioandroid : 3
    pkg_app_dev_geode_export --> pkg_scenes_dev_geode_render_scene : 3
    pkg_scenes_dev_geode_render --> pkg_scenes_dev_geode_analysis : 3
    pkg_scenes_dev_geode_render_offscreen --> pkg_scenes_dev_geode_render : 3
    pkg_scenes_dev_geode_render_offscreen --> pkg_scenes_dev_geode_render_fluid : 3
    pkg_app_dev_geode_ui --> pkg_app_dev_geode_export : 3
    pkg_app_dev_geode_data --> pkg_app_dev_geode_editor : 3
    pkg_app_dev_geode_data --> pkg_scenes_dev_geode_render : 3
    pkg_scenes_dev_geode_analysis --> pkg_audio_core_dev_geode_engine_audio : 2
    pkg_app_dev_geode_audio --> pkg_audio_android_dev_geode_engine_audioandroid : 2
    pkg_app_dev_geode_editor --> pkg_app_dev_geode_export : 2
    pkg_audio_android_dev_geode_engine_audioandroid --> pkg_audio_core_dev_geode_engine_audio : 2
    pkg_app_dev_geode_export --> pkg_scenes_dev_geode_analysis : 2
    pkg_scenes_dev_geode_render --> pkg_scenes_dev_geode_render_fluid : 2
    pkg_scenes_dev_geode_render_offscreen --> pkg_scenes_dev_geode_render_scene : 2
    pkg_scenes_dev_geode_render_scene --> pkg_scenes_dev_geode_render_compute : 2
    pkg_app_dev_geode_ui --> pkg_app_dev_geode_editor : 2
    pkg_app_dev_geode_audio --> pkg_audio_core_dev_geode_engine_audio : 1
    pkg_app_dev_geode --> pkg_app_dev_geode_ui : 1
    pkg_app_dev_geode_audio --> pkg_scenes_dev_geode_analysis : 1
    pkg_scenes_dev_geode_render --> pkg_gl_dev_geode_engine_gl : 1
    pkg_scenes_dev_geode_render_fluid --> pkg_app_dev_geode_billing : 1
    pkg_scenes_dev_geode_render_offscreen --> pkg_scenes_dev_geode_analysis : 1
    pkg_app_dev_geode_wallpaper --> pkg_scenes_dev_geode_render : 1
    pkg_scenes_dev_geode_analysis --> pkg_scenes_dev_geode_render_scene : 1
    pkg_app_dev_geode_audio --> pkg_app_dev_geode_audio_dsp : 1
    pkg_app_dev_geode_data --> pkg_app_dev_geode_ui : 1
    pkg_app_dev_geode_di --> pkg_app_dev_geode_data : 1
    pkg_app_dev_geode_wallpaper --> pkg_scenes_dev_geode_analysis : 1
```

## app :: dev.geode

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode {
        class GeodeApp["GeodeApp"] {
            <<class>>
        }
        class GeodeContainer["GeodeContainer"] {
            <<class>>
        }
        class RingLog["RingLog"] {
            <<object>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_ui["~.ui"] {
            117 types
        }
    }

    namespace android.app {
        class ext_Application["Application"] {
            <<class>>
        }
    }

    GeodeApp --> GeodeContainer
    GeodeContainer ..> pkg_app_dev_geode_ui
    ext_Application <|-- GeodeApp
```

## app :: dev.geode.di

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.di {
        class DataModule["DataModule"] {
            <<object>>
        }
        class GeneralPrefs["GeneralPrefs"] {
            <<annotation>>
        }
        class PlayerSessionProvider["PlayerSessionProvider"] {
            <<class>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_data["~.data"] {
            100 types
        }
    }

    DataModule ..> pkg_app_dev_geode_data
```

## app :: dev.geode.ui

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.ui {
        class AbLoop["AbLoop"] {
            <<data class>>
        }
        class AbSnapshotState["AbSnapshotState"] {
            <<data class>>
        }
        class AnalysisState["AnalysisState"] {
            <<sealed interface>>
        }
        class ArtworkCache["ArtworkCache"] {
            <<object>>
        }
        class AutoVisualsController["AutoVisualsController"] {
            <<class>>
        }
        class AutoVisualsPrefsStore["AutoVisualsPrefsStore"] {
            <<class>>
        }
        class BuiltInPresets["BuiltInPresets"] {
            <<object>>
        }
        class CaptureController["CaptureController"] {
            <<class>>
        }
        class ColorDerive["ColorDerive"] {
            <<object>>
        }
        class CornerStyle["CornerStyle"] {
            <<enumeration>>
        }
        class CrystalNavItem["CrystalNavItem"] {
            <<data class>>
        }
        class CustomizeSummary["CustomizeSummary"] {
            <<object>>
        }
        class DeviceTrack["DeviceTrack"] {
            <<data class>>
        }
        class DismissProgress["DismissProgress"] {
            <<class>>
        }
        class ExportController["ExportController"] {
            <<class>>
        }
        class ExportPhase["ExportPhase"] {
            <<sealed interface>>
        }
        class ExportUiState["ExportUiState"] {
            <<data class>>
        }
        class ExternalAudioState["ExternalAudioState"] {
            <<data class>>
        }
        class FolderTree["FolderTree"] {
            <<object>>
        }
        class FontColorChoice["FontColorChoice"] {
            <<data class>>
        }
        class GeodeAppState["GeodeAppState"] {
            <<class>>
        }
        class GeodeDestination["GeodeDestination"] {
            <<enumeration>>
        }
        class GeodeUserData["GeodeUserData"] {
            <<data class>>
        }
        class GuiPrefs["GuiPrefs"] {
            <<data class>>
        }
        class HelpTopic["HelpTopic"] {
            <<enumeration>>
        }
        class LayersBus["LayersBus"] {
            <<object>>
        }
        class LayersUiState["LayersUiState"] {
            <<data class>>
        }
        class LibraryBrowse["LibraryBrowse"] {
            <<object>>
        }
        class LibrarySort["LibrarySort"] {
            <<enumeration>>
        }
        class LibraryState["LibraryState"] {
            <<data class>>
        }
        class LibraryTrack["LibraryTrack"] {
            <<data class>>
        }
        class LibraryUiState["LibraryUiState"] {
            <<data class>>
        }
        class LibraryViewModel["LibraryViewModel"] {
            <<class>>
        }
        class ListeningTracker["ListeningTracker"] {
            <<class>>
        }
        class LyricLine["LyricLine"] {
            <<data class>>
        }
        class Lyrics["Lyrics"] {
            <<data class>>
        }
        class LyricsLoader["LyricsLoader"] {
            <<object>>
        }
        class MainActivity["MainActivity"] {
            <<class>>
        }
        class MicState["MicState"] {
            <<data class>>
        }
        class MilkFile["MilkFile"] {
            <<data class>>
        }
        class MilkImportController["MilkImportController"] {
            <<class>>
        }
        class ModulationController["ModulationController"] {
            <<class>>
        }
        class MusicLibraryController["MusicLibraryController"] {
            <<class>>
        }
        class NavEntry["NavEntry"] {
            <<data class>>
        }
        class ParamHistoryState["ParamHistoryState"] {
            <<data class>>
        }
        class PendingExport["PendingExport"] {
            <<data class>>
        }
        class PlaybackFades["PlaybackFades"] {
            <<class>>
        }
        class PlaybackQueue["PlaybackQueue"] {
            <<object>>
        }
        class PlaybackRepository["PlaybackRepository"] {
            <<interface>>
        }
        class PlayerPanel["PlayerPanel"] {
            <<enumeration>>
        }
        class PlayerPosition["PlayerPosition"] {
            <<enumeration>>
        }
        class PlayerSession["PlayerSession"] {
            <<class>>
        }
        class PlayerSettingsController["PlayerSettingsController"] {
            <<class>>
        }
        class PlayerUiState["PlayerUiState"] {
            <<data class>>
        }
        class PlayerViewModel["PlayerViewModel"] {
            <<class>>
        }
        class PresetLibraryController["PresetLibraryController"] {
            <<class>>
        }
        class PresetLink["PresetLink"] {
            <<object>>
        }
        class PresetLinkImport["PresetLinkImport"] {
            <<sealed interface>>
        }
        class QueueController["QueueController"] {
            <<class>>
        }
        class QueueTrack["QueueTrack"] {
            <<data class>>
        }
        class QueueUiState["QueueUiState"] {
            <<data class>>
        }
        class SavedPalettes["SavedPalettes"] {
            <<class>>
        }
        class SearchTrackRow["SearchTrackRow"] {
            <<data class>>
        }
        class SessionPlaybackRepository["SessionPlaybackRepository"] {
            <<class>>
        }
        class SessionVisualizerRepository["SessionVisualizerRepository"] {
            <<class>>
        }
        class SettingsViewModel["SettingsViewModel"] {
            <<class>>
        }
        class SharedPrefsUserDataRepository["SharedPrefsUserDataRepository"] {
            <<class>>
        }
        class StudioUiState["StudioUiState"] {
            <<data class>>
        }
        class StudioViewModel["StudioViewModel"] {
            <<class>>
        }
        class TakeController["TakeController"] {
            <<class>>
        }
        class TakeUiState["TakeUiState"] {
            <<data class>>
        }
        class TextureController["TextureController"] {
            <<class>>
        }
        class ThemeContrast["ThemeContrast"] {
            <<object>>
        }
        class ThemeStore["ThemeStore"] {
            <<class>>
        }
        class TrackAnalysisController["TrackAnalysisController"] {
            <<class>>
        }
        class TrackColorController["TrackColorController"] {
            <<class>>
        }
        class TrackLibrary["TrackLibrary"] {
            <<class>>
        }
        class TutorialStep["TutorialStep"] {
            <<enumeration>>
        }
        class UserDataRepository["UserDataRepository"] {
            <<interface>>
        }
        class UserIntent["UserIntent"] {
            <<enumeration>>
        }
        class VideoFrameCache["VideoFrameCache"] {
            <<object>>
        }
        class VisualSettingsController["VisualSettingsController"] {
            <<class>>
        }
        class VisualizerRepository["VisualizerRepository"] {
            <<interface>>
        }
        class VisualizerTouch["VisualizerTouch"] {
            <<class>>
        }
        class VisualsViewModel["VisualsViewModel"] {
            <<class>>
        }
        class VizApply["VizApply"] {
            <<data class>>
        }
        class VizPlaylistEntry["VizPlaylistEntry"] {
            <<data class>>
        }
        class VizStateStore["VizStateStore"] {
            <<class>>
        }
        class VizUiState["VizUiState"] {
            <<data class>>
        }
        class AnalysisState_Failed["AnalysisState.Failed"] {
            <<data class>>
        }
        class AnalysisState_Idle["AnalysisState.Idle"] {
            <<data object>>
        }
        class AnalysisState_Running["AnalysisState.Running"] {
            <<data class>>
        }
        class AutoVisualsController_Host["AutoVisualsController.Host"] {
            <<interface>>
        }
        class BuiltInPresets_Look["BuiltInPresets.Look"] {
            <<data class>>
        }
        class CaptureController_Host["CaptureController.Host"] {
            <<interface>>
        }
        class ExportController_Host["ExportController.Host"] {
            <<interface>>
        }
        class ExportPhase_Done["ExportPhase.Done"] {
            <<data class>>
        }
        class ExportPhase_Failed["ExportPhase.Failed"] {
            <<data class>>
        }
        class ExportPhase_Idle["ExportPhase.Idle"] {
            <<data object>>
        }
        class ExportPhase_Loading["ExportPhase.Loading"] {
            <<data object>>
        }
        class ExportPhase_Running["ExportPhase.Running"] {
            <<data class>>
        }
        class ListeningTracker_Host["ListeningTracker.Host"] {
            <<interface>>
        }
        class ModulationController_Host["ModulationController.Host"] {
            <<interface>>
        }
        class MusicLibraryController_FileMeta["MusicLibraryController.FileMeta"] {
            <<data class>>
        }
        class PlaybackFades_Host["PlaybackFades.Host"] {
            <<interface>>
        }
        class PlaybackQueue_Window["PlaybackQueue.Window"] {
            <<data class>>
        }
        class PlayerSettingsController_Host["PlayerSettingsController.Host"] {
            <<interface>>
        }
        class PresetLibraryController_Host["PresetLibraryController.Host"] {
            <<interface>>
        }
        class PresetLinkImport_Imported["PresetLinkImport.Imported"] {
            <<data class>>
        }
        class PresetLinkImport_NotALink["PresetLinkImport.NotALink"] {
            <<data object>>
        }
        class PresetLinkImport_Unreadable["PresetLinkImport.Unreadable"] {
            <<data object>>
        }
        class QueueController_Host["QueueController.Host"] {
            <<interface>>
        }
        class TakeController_Host["TakeController.Host"] {
            <<interface>>
        }
        class TextureController_Host["TextureController.Host"] {
            <<interface>>
        }
        class TrackAnalysisController_Host["TrackAnalysisController.Host"] {
            <<interface>>
        }
        class TrackColorController_Host["TrackColorController.Host"] {
            <<interface>>
        }
        class VisualSettingsController_Host["VisualSettingsController.Host"] {
            <<interface>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode["~"] {
            3 types
        }
        class pkg_app_dev_geode_ui_theme["~.ui.theme"] {
            16 types
        }
        class pkg_app_dev_geode_editor["~.editor"] {
            98 types
        }
        class pkg_app_dev_geode_export["~.export"] {
            109 types
        }
        class pkg_app_dev_geode_data["~.data"] {
            100 types
        }
        class pkg_app_dev_geode_audio["~.audio"] {
            21 types
        }
        class pkg_scenes_dev_geode_render["~.render"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_scene["~.render.scene"] {
            49 types
        }
        class pkg_scenes_dev_geode_analysis["~.analysis"] {
            22 types
        }
    }

    namespace androidx.activity {
        class ext_ComponentActivity["ComponentActivity"] {
            <<class>>
        }
    }

    AnalysisState <|.. AnalysisState_Failed
    AnalysisState <|.. AnalysisState_Idle
    AnalysisState <|.. AnalysisState_Running
    ExportPhase <|.. ExportPhase_Done
    ExportPhase <|.. ExportPhase_Failed
    ExportPhase <|.. ExportPhase_Idle
    ExportPhase <|.. ExportPhase_Loading
    ExportPhase <|.. ExportPhase_Running
    PresetLinkImport <|.. PresetLinkImport_Imported
    PresetLinkImport <|.. PresetLinkImport_NotALink
    PresetLinkImport <|.. PresetLinkImport_Unreadable
    PlaybackRepository <|.. SessionPlaybackRepository
    VisualizerRepository <|.. SessionVisualizerRepository
    UserDataRepository <|.. SharedPrefsUserDataRepository
    AutoVisualsController --> AutoVisualsPrefsStore
    AutoVisualsController --> MilkFile
    AutoVisualsController_Host --> VizUiState
    BuiltInPresets --> BuiltInPresets_Look
    CaptureController --> ExternalAudioState
    CaptureController --> MicState
    ExportController --> ExportUiState
    ExportController --> StudioUiState
    ExportController_Host --> GuiPrefs
    ExportUiState --> ExportPhase
    GeodeAppState --> GeodeDestination
    GeodeUserData --> GuiPrefs
    GuiPrefs --> CornerStyle
    GuiPrefs --> PlayerPosition
    GuiPrefs --> UserIntent
    LibraryState --> LibraryTrack
    LibraryUiState --> DeviceTrack
    LibraryUiState --> LibrarySort
    Lyrics --> LyricLine
    MainActivity --> SettingsViewModel
    MainActivity --> VisualsViewModel
    ModulationController --> AbSnapshotState
    ModulationController --> ParamHistoryState
    MusicLibraryController --> DeviceTrack
    MusicLibraryController --> LibraryState
    MusicLibraryController --> LibraryTrack
    NavEntry --> CrystalNavItem
    NavEntry --> GeodeDestination
    PlaybackQueue --> QueueTrack
    PlaybackQueue_Window --> QueueTrack
    PlaybackRepository --> AbLoop
    PlaybackRepository --> PlayerUiState
    PlaybackRepository --> QueueUiState
    PlayerSettingsController --> GuiPrefs
    PlayerSettingsController --> UserDataRepository
    PresetLibraryController_Host --> VizUiState
    QueueController --> AbLoop
    QueueController --> QueueTrack
    QueueController --> QueueUiState
    QueueController_Host --> DeviceTrack
    QueueController_Host --> LibraryTrack
    QueueUiState --> QueueTrack
    SessionPlaybackRepository --> AbLoop
    SessionPlaybackRepository --> PlayerSession
    SessionPlaybackRepository --> PlayerUiState
    SessionPlaybackRepository --> QueueUiState
    SessionVisualizerRepository --> PlayerSession
    SessionVisualizerRepository --> VizUiState
    SharedPrefsUserDataRepository --> GeodeUserData
    SharedPrefsUserDataRepository --> GuiPrefs
    StudioUiState --> ExportPhase
    TakeController --> TakeUiState
    TakeController_Host --> VizUiState
    TrackAnalysisController_Host --> GuiPrefs
    TrackAnalysisController_Host --> VizUiState
    TutorialStep --> GeodeDestination
    UserDataRepository --> GeodeUserData
    UserDataRepository --> GuiPrefs
    UserIntent --> GeodeDestination
    VisualSettingsController --> VizApply
    VisualSettingsController --> VizStateStore
    VisualSettingsController_Host --> GuiPrefs
    VisualizerRepository --> VizUiState
    VizStateStore --> AutoVisualsPrefsStore
    VizUiState --> AnalysisState
    VizUiState --> VizPlaylistEntry
    AutoVisualsController ..> VizPlaylistEntry
    AutoVisualsController_Host ..> MilkFile
    AutoVisualsPrefsStore ..> VizUiState
    ListeningTracker_Host ..> QueueTrack
    MilkImportController ..> MilkFile
    MusicLibraryController ..> MusicLibraryController_FileMeta
    PlaybackQueue ..> DeviceTrack
    PlaybackQueue ..> LibraryTrack
    PlayerSettingsController_Host ..> GuiPrefs
    QueueController ..> PlaybackQueue
    ThemeStore ..> GuiPrefs
    TrackAnalysisController ..> GuiPrefs
    TrackLibrary ..> LibraryTrack
    VizStateStore ..> VizUiState
    pkg_app_dev_geode ..> UserDataRepository
    AbSnapshotState ..> pkg_scenes_dev_geode_render_scene
    BuiltInPresets ..> pkg_app_dev_geode_data
    BuiltInPresets ..> pkg_scenes_dev_geode_render_scene
    BuiltInPresets_Look ..> pkg_scenes_dev_geode_render_scene
    CrystalNavItem ..> pkg_app_dev_geode_ui_theme
    ExportController_Host ..> pkg_scenes_dev_geode_analysis
    ExportController_Host ..> pkg_scenes_dev_geode_render_scene
    ExternalAudioState ..> pkg_app_dev_geode_audio : 3
    GeodeDestination ..> pkg_app_dev_geode_ui_theme
    GeodeUserData ..> pkg_app_dev_geode_ui_theme
    GuiPrefs ..> pkg_scenes_dev_geode_analysis
    LayersUiState ..> pkg_scenes_dev_geode_render
    LibraryState ..> pkg_app_dev_geode_data
    LibraryUiState ..> pkg_app_dev_geode_data
    ListeningTracker ..> pkg_app_dev_geode_data : 2
    MicState ..> pkg_app_dev_geode_audio : 2
    ModulationController ..> pkg_app_dev_geode_data
    ModulationController ..> pkg_scenes_dev_geode_render : 2
    ModulationController_Host ..> pkg_scenes_dev_geode_render_scene
    PendingExport ..> pkg_app_dev_geode_export : 2
    PlayerSettingsController ..> pkg_scenes_dev_geode_analysis
    PlayerSettingsController ..> pkg_app_dev_geode_audio : 2
    PlayerSettingsController ..> pkg_app_dev_geode_data : 2
    PlayerSettingsController ..> pkg_app_dev_geode_ui_theme
    PresetLibraryController ..> pkg_app_dev_geode_data : 3
    SavedPalettes ..> pkg_app_dev_geode_data : 2
    SessionVisualizerRepository ..> pkg_scenes_dev_geode_analysis
    SharedPrefsUserDataRepository ..> pkg_app_dev_geode_ui_theme
    StudioUiState ..> pkg_app_dev_geode_export
    TakeController ..> pkg_app_dev_geode_data : 3
    TakeUiState ..> pkg_app_dev_geode_data
    TextureController ..> pkg_app_dev_geode_data
    TrackAnalysisController ..> pkg_scenes_dev_geode_analysis : 2
    TrackColorController_Host ..> pkg_scenes_dev_geode_render_scene
    UserDataRepository ..> pkg_app_dev_geode_ui_theme
    VisualSettingsController ..> pkg_scenes_dev_geode_analysis : 2
    VisualizerRepository ..> pkg_scenes_dev_geode_analysis
    VisualizerTouch ..> pkg_scenes_dev_geode_render
    VizUiState ..> pkg_scenes_dev_geode_analysis
    VizUiState ..> pkg_app_dev_geode_data
    VizUiState ..> pkg_scenes_dev_geode_render
    VizUiState ..> pkg_scenes_dev_geode_render_scene : 2
    pkg_app_dev_geode_data ..> PresetLink
    AutoVisualsController_Host ..> pkg_scenes_dev_geode_analysis
    AutoVisualsController_Host ..> pkg_app_dev_geode_data
    CaptureController ..> pkg_app_dev_geode_audio : 2
    CustomizeSummary ..> pkg_app_dev_geode_data
    CustomizeSummary ..> pkg_scenes_dev_geode_render_scene
    ExportController_Host ..> pkg_app_dev_geode_data
    ExportController_Host ..> pkg_app_dev_geode_editor
    ExportController_Host ..> pkg_scenes_dev_geode_render : 2
    MilkImportController ..> pkg_app_dev_geode_data
    ModulationController ..> pkg_scenes_dev_geode_render_scene : 3
    PresetLibraryController_Host ..> pkg_app_dev_geode_data
    TakeController ..> pkg_app_dev_geode_editor
    TakeController_Host ..> pkg_scenes_dev_geode_render_scene
    ThemeStore ..> pkg_app_dev_geode_ui_theme : 2
    VisualSettingsController ..> pkg_app_dev_geode_data
    VisualSettingsController ..> pkg_scenes_dev_geode_render
    VisualSettingsController ..> pkg_scenes_dev_geode_render_scene
    VisualSettingsController_Host ..> pkg_app_dev_geode_data
    ext_ComponentActivity <|-- MainActivity
```

## app :: dev.geode.ui.theme

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.ui.theme {
        class ParsedGlyph["ParsedGlyph"] {
            <<class>>
        }
        class StoneComponent["StoneComponent"] {
            <<enumeration>>
        }
        class StoneHapticCue["StoneHapticCue"] {
            <<enumeration>>
        }
        class StoneIcon["StoneIcon"] {
            <<enumeration>>
        }
        class StoneIconDefaults["StoneIconDefaults"] {
            <<object>>
        }
        class StoneIconFinish["StoneIconFinish"] {
            <<enumeration>>
        }
        class StoneIconGlyph["StoneIconGlyph"] {
            <<data class>>
        }
        class StoneIconLayer["StoneIconLayer"] {
            <<class>>
        }
        class StoneMaterial["StoneMaterial"] {
            <<data class>>
        }
        class StoneMotion["StoneMotion"] {
            <<data class>>
        }
        class StonePalette["StonePalette"] {
            <<data class>>
        }
        class StoneSounds["StoneSounds"] {
            <<data class>>
        }
        class StoneState["StoneState"] {
            <<enumeration>>
        }
        class StoneStateArt["StoneStateArt"] {
            <<data class>>
        }
        class ThemePack["ThemePack"] {
            <<data class>>
        }
        class ThemePackCatalog["ThemePackCatalog"] {
            <<object>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_ui["~.ui"] {
            117 types
        }
    }

    ThemePack --> StoneComponent
    ThemePack --> StoneMaterial
    ThemePack --> StoneMotion
    ThemePack --> StonePalette
    ThemePack --> StoneSounds
    ThemePackCatalog --> ThemePack
    StoneStateArt ..> StoneState
    ThemePack ..> StoneStateArt
    pkg_app_dev_geode_ui ..> StoneIcon : 2
    pkg_app_dev_geode_ui ..> ThemePack : 5
    pkg_app_dev_geode_ui ..> ThemePackCatalog
```

## app :: dev.geode.editor

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.editor {
        class AutoCut["AutoCut"] {
            <<object>>
        }
        class AutoCutMiss["AutoCutMiss"] {
            <<sealed interface>>
        }
        class AutoCutResult["AutoCutResult"] {
            <<sealed interface>>
        }
        class AutoCutSettings["AutoCutSettings"] {
            <<data class>>
        }
        class BezierCurve["BezierCurve"] {
            <<data class>>
        }
        class Clip["Clip"] {
            <<data class>>
        }
        class ClipContent["ClipContent"] {
            <<sealed interface>>
        }
        class ClipEdge["ClipEdge"] {
            <<enumeration>>
        }
        class ClipId["ClipId"] {
            <<value class>>
        }
        class ClipSplit["ClipSplit"] {
            <<data class>>
        }
        class EaseShape["EaseShape"] {
            <<enumeration>>
        }
        class EditError["EditError"] {
            <<sealed interface>>
        }
        class EditResult["EditResult"] {
            <<sealed interface>>
        }
        class EditorProject["EditorProject"] {
            <<data class>>
        }
        class Interpolation["Interpolation"] {
            <<sealed interface>>
        }
        class Keyframe["Keyframe"] {
            <<data class>>
        }
        class KeyframeError["KeyframeError"] {
            <<sealed interface>>
        }
        class KeyframeId["KeyframeId"] {
            <<value class>>
        }
        class KeyframeResult["KeyframeResult"] {
            <<sealed interface>>
        }
        class KeyframeSheet["KeyframeSheet"] {
            <<data class>>
        }
        class KeyframeTrack["KeyframeTrack"] {
            <<data class>>
        }
        class Lane["Lane"] {
            <<data class>>
        }
        class LaneId["LaneId"] {
            <<value class>>
        }
        class LaneKind["LaneKind"] {
            <<sealed interface>>
        }
        class Marker["Marker"] {
            <<data class>>
        }
        class MarkerColour["MarkerColour"] {
            <<enumeration>>
        }
        class MarkerId["MarkerId"] {
            <<value class>>
        }
        class MarkerOrigin["MarkerOrigin"] {
            <<sealed interface>>
        }
        class MarkerSet["MarkerSet"] {
            <<data class>>
        }
        class OverlapPolicy["OverlapPolicy"] {
            <<enumeration>>
        }
        class OverlayBlend["OverlayBlend"] {
            <<enumeration>>
        }
        class ParamId["ParamId"] {
            <<value class>>
        }
        class ParamKind["ParamKind"] {
            <<enumeration>>
        }
        class ParamValue["ParamValue"] {
            <<sealed interface>>
        }
        class PerformanceKeyframes["PerformanceKeyframes"] {
            <<object>>
        }
        class PerformanceSample["PerformanceSample"] {
            <<data class>>
        }
        class PlaceOutcome["PlaceOutcome"] {
            <<sealed interface>>
        }
        class RippleScope["RippleScope"] {
            <<enumeration>>
        }
        class RippleShift["RippleShift"] {
            <<data class>>
        }
        class SnapContext["SnapContext"] {
            <<data class>>
        }
        class SnapMode["SnapMode"] {
            <<sealed interface>>
        }
        class SnapResult["SnapResult"] {
            <<data class>>
        }
        class SnapTarget["SnapTarget"] {
            <<enumeration>>
        }
        class TapInSession["TapInSession"] {
            <<data class>>
        }
        class TapInSettings["TapInSettings"] {
            <<data class>>
        }
        class TapResult["TapResult"] {
            <<sealed interface>>
        }
        class Timeline["Timeline"] {
            <<data class>>
        }
        class TransientEnvelope["TransientEnvelope"] {
            <<data class>>
        }
        class TransientHit["TransientHit"] {
            <<data class>>
        }
        class TransientSource["TransientSource"] {
            <<enumeration>>
        }
        class AutoCutMiss_EmptyEnvelope["AutoCutMiss.EmptyEnvelope"] {
            <<data object>>
        }
        class AutoCutMiss_NoTransients["AutoCutMiss.NoTransients"] {
            <<data object>>
        }
        class AutoCutMiss_WindowTooShort["AutoCutMiss.WindowTooShort"] {
            <<data class>>
        }
        class AutoCutResult_NoCuts["AutoCutResult.NoCuts"] {
            <<data class>>
        }
        class AutoCutResult_Suggested["AutoCutResult.Suggested"] {
            <<data class>>
        }
        class ClipContent_Audio["ClipContent.Audio"] {
            <<data class>>
        }
        class ClipContent_Overlay["ClipContent.Overlay"] {
            <<data class>>
        }
        class ClipContent_Scene["ClipContent.Scene"] {
            <<data class>>
        }
        class ClipContent_Still["ClipContent.Still"] {
            <<data class>>
        }
        class ClipContent_Text["ClipContent.Text"] {
            <<data class>>
        }
        class ClipContent_Video["ClipContent.Video"] {
            <<data class>>
        }
        class EditError_ClipNotFound["EditError.ClipNotFound"] {
            <<data class>>
        }
        class EditError_LaneLocked["EditError.LaneLocked"] {
            <<data class>>
        }
        class EditError_LaneNotFound["EditError.LaneNotFound"] {
            <<data class>>
        }
        class EditError_NeedsSplit["EditError.NeedsSplit"] {
            <<data class>>
        }
        class EditError_OutsideClip["EditError.OutsideClip"] {
            <<data object>>
        }
        class EditError_Overlaps["EditError.Overlaps"] {
            <<data class>>
        }
        class EditError_TooShort["EditError.TooShort"] {
            <<data object>>
        }
        class EditError_WrongLaneKind["EditError.WrongLaneKind"] {
            <<data class>>
        }
        class EditResult_Applied["EditResult.Applied"] {
            <<data class>>
        }
        class EditResult_Rejected["EditResult.Rejected"] {
            <<data class>>
        }
        class Interpolation_Custom["Interpolation.Custom"] {
            <<data class>>
        }
        class Interpolation_Ease["Interpolation.Ease"] {
            <<data class>>
        }
        class Interpolation_Hold["Interpolation.Hold"] {
            <<data object>>
        }
        class Interpolation_Linear["Interpolation.Linear"] {
            <<data object>>
        }
        class KeyframeError_KeyNotFound["KeyframeError.KeyNotFound"] {
            <<data class>>
        }
        class KeyframeError_KindMismatch["KeyframeError.KindMismatch"] {
            <<data class>>
        }
        class KeyframeResult_Applied["KeyframeResult.Applied"] {
            <<data class>>
        }
        class KeyframeResult_Rejected["KeyframeResult.Rejected"] {
            <<data class>>
        }
        class LaneKind_Audio["LaneKind.Audio"] {
            <<data object>>
        }
        class LaneKind_Media["LaneKind.Media"] {
            <<data object>>
        }
        class LaneKind_Overlay["LaneKind.Overlay"] {
            <<data object>>
        }
        class LaneKind_Text["LaneKind.Text"] {
            <<data object>>
        }
        class LaneKind_Visual["LaneKind.Visual"] {
            <<data object>>
        }
        class MarkerOrigin_Detected["MarkerOrigin.Detected"] {
            <<data class>>
        }
        class MarkerOrigin_Manual["MarkerOrigin.Manual"] {
            <<data object>>
        }
        class MarkerOrigin_TappedIn["MarkerOrigin.TappedIn"] {
            <<data class>>
        }
        class ParamValue_Choice["ParamValue.Choice"] {
            <<data class>>
        }
        class ParamValue_Colour["ParamValue.Colour"] {
            <<data class>>
        }
        class ParamValue_Scalar["ParamValue.Scalar"] {
            <<data class>>
        }
        class ParamValue_Toggle["ParamValue.Toggle"] {
            <<data class>>
        }
        class ParamValue_Vector2["ParamValue.Vector2"] {
            <<data class>>
        }
        class PlaceOutcome_Blocked["PlaceOutcome.Blocked"] {
            <<data class>>
        }
        class PlaceOutcome_Placed["PlaceOutcome.Placed"] {
            <<data class>>
        }
        class SnapMode_Free["SnapMode.Free"] {
            <<data object>>
        }
        class SnapMode_Magnetic["SnapMode.Magnetic"] {
            <<data class>>
        }
        class TapResult_Debounced["TapResult.Debounced"] {
            <<data class>>
        }
        class TapResult_Placed["TapResult.Placed"] {
            <<data class>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_ui["~.ui"] {
            117 types
        }
        class pkg_app_dev_geode_export["~.export"] {
            109 types
        }
        class pkg_app_dev_geode_data["~.data"] {
            100 types
        }
    }

    AutoCutMiss <|.. AutoCutMiss_EmptyEnvelope
    AutoCutMiss <|.. AutoCutMiss_NoTransients
    AutoCutMiss <|.. AutoCutMiss_WindowTooShort
    AutoCutResult <|.. AutoCutResult_NoCuts
    AutoCutResult <|.. AutoCutResult_Suggested
    ClipContent <|.. ClipContent_Audio
    ClipContent <|.. ClipContent_Overlay
    ClipContent <|.. ClipContent_Scene
    ClipContent <|.. ClipContent_Still
    ClipContent <|.. ClipContent_Text
    ClipContent <|.. ClipContent_Video
    EditError <|.. EditError_ClipNotFound
    EditError <|.. EditError_LaneLocked
    EditError <|.. EditError_LaneNotFound
    EditError <|.. EditError_NeedsSplit
    EditError <|.. EditError_OutsideClip
    EditError <|.. EditError_Overlaps
    EditError <|.. EditError_TooShort
    EditError <|.. EditError_WrongLaneKind
    EditResult <|.. EditResult_Applied
    EditResult <|.. EditResult_Rejected
    Interpolation <|.. Interpolation_Custom
    Interpolation <|.. Interpolation_Ease
    Interpolation <|.. Interpolation_Hold
    Interpolation <|.. Interpolation_Linear
    KeyframeError <|.. KeyframeError_KeyNotFound
    KeyframeError <|.. KeyframeError_KindMismatch
    KeyframeResult <|.. KeyframeResult_Applied
    KeyframeResult <|.. KeyframeResult_Rejected
    LaneKind <|.. LaneKind_Audio
    LaneKind <|.. LaneKind_Media
    LaneKind <|.. LaneKind_Overlay
    LaneKind <|.. LaneKind_Text
    LaneKind <|.. LaneKind_Visual
    MarkerOrigin <|.. MarkerOrigin_Detected
    MarkerOrigin <|.. MarkerOrigin_Manual
    MarkerOrigin <|.. MarkerOrigin_TappedIn
    ParamValue <|.. ParamValue_Choice
    ParamValue <|.. ParamValue_Colour
    ParamValue <|.. ParamValue_Scalar
    ParamValue <|.. ParamValue_Toggle
    ParamValue <|.. ParamValue_Vector2
    PlaceOutcome <|.. PlaceOutcome_Blocked
    PlaceOutcome <|.. PlaceOutcome_Placed
    SnapMode <|.. SnapMode_Free
    SnapMode <|.. SnapMode_Magnetic
    TapResult <|.. TapResult_Debounced
    TapResult <|.. TapResult_Placed
    AutoCutResult --> AutoCutSettings
    AutoCutResult --> TransientHit
    AutoCutResult_NoCuts --> AutoCutMiss
    AutoCutResult_Suggested --> AutoCutSettings
    AutoCutResult_Suggested --> TransientHit
    AutoCutSettings --> TransientSource
    Clip --> ClipContent
    Clip --> ClipId
    ClipContent --> LaneKind
    ClipContent --> OverlayBlend
    ClipContent_Audio --> LaneKind
    ClipContent_Overlay --> LaneKind
    ClipContent_Overlay --> OverlayBlend
    ClipContent_Scene --> LaneKind
    ClipContent_Still --> LaneKind
    ClipContent_Text --> LaneKind
    ClipContent_Video --> LaneKind
    ClipSplit --> Clip
    EaseShape --> BezierCurve
    EditError --> LaneId
    EditError --> LaneKind
    EditError_ClipNotFound --> ClipId
    EditError_LaneLocked --> LaneId
    EditError_LaneNotFound --> LaneId
    EditError_NeedsSplit --> ClipId
    EditError_Overlaps --> ClipId
    EditError_WrongLaneKind --> LaneId
    EditError_WrongLaneKind --> LaneKind
    EditResult --> ClipId
    EditResult --> RippleShift
    EditResult --> Timeline
    EditResult_Applied --> ClipId
    EditResult_Applied --> RippleShift
    EditResult_Applied --> Timeline
    EditResult_Rejected --> EditError
    EditorProject --> KeyframeSheet
    EditorProject --> MarkerSet
    EditorProject --> Timeline
    Interpolation_Custom --> BezierCurve
    Interpolation_Ease --> EaseShape
    Keyframe --> Interpolation
    Keyframe --> Interpolation_Linear
    Keyframe --> KeyframeId
    Keyframe --> ParamValue
    KeyframeError --> ParamKind
    KeyframeError_KeyNotFound --> KeyframeId
    KeyframeError_KindMismatch --> ParamKind
    KeyframeResult_Applied --> KeyframeTrack
    KeyframeResult_Rejected --> KeyframeError
    KeyframeSheet --> KeyframeTrack
    KeyframeTrack --> ClipId
    KeyframeTrack --> Keyframe
    KeyframeTrack --> ParamId
    KeyframeTrack --> ParamKind
    Lane --> Clip
    Lane --> LaneId
    Lane --> LaneKind
    Marker --> MarkerColour
    Marker --> MarkerId
    Marker --> MarkerOrigin
    Marker --> MarkerOrigin_Manual
    MarkerSet --> Marker
    ParamValue --> ParamKind
    ParamValue_Choice --> ParamKind
    ParamValue_Colour --> ParamKind
    ParamValue_Scalar --> ParamKind
    ParamValue_Toggle --> ParamKind
    ParamValue_Vector2 --> ParamKind
    PerformanceSample --> ParamId
    PlaceOutcome_Blocked --> EditError
    PlaceOutcome_Placed --> Lane
    RippleShift --> ClipId
    RippleShift --> RippleScope
    SnapMode --> SnapTarget
    SnapMode_Magnetic --> SnapTarget
    SnapResult --> SnapTarget
    TapInSession --> Marker
    TapInSession --> TapInSettings
    TapInSettings --> MarkerColour
    TapResult --> Marker
    TapResult --> TapInSession
    TapResult_Debounced --> TapInSession
    TapResult_Placed --> Marker
    TapResult_Placed --> TapInSession
    Timeline --> Lane
    EditorProject ..> EditResult
    KeyframeSheet ..> ClipId
    KeyframeSheet ..> RippleShift
    KeyframeTrack ..> KeyframeId
    KeyframeTrack ..> KeyframeResult
    KeyframeTrack ..> ParamValue
    KeyframeTrack ..> RippleShift
    Lane ..> ClipId
    Lane ..> RippleShift
    MarkerSet ..> MarkerId
    MarkerSet ..> RippleShift
    Timeline ..> Clip
    Timeline ..> ClipId
    Timeline ..> EditResult
    Timeline ..> LaneId
    ClipContent ..> pkg_app_dev_geode_export
    ClipContent_Video ..> pkg_app_dev_geode_export
    pkg_app_dev_geode_data ..> Timeline : 3
    pkg_app_dev_geode_ui ..> Timeline : 2
```

## app :: dev.geode.export

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.export {
        class AudioTranscoder["AudioTranscoder"] {
            <<class>>
        }
        class Chapter["Chapter"] {
            <<data class>>
        }
        class ChapterFormat["ChapterFormat"] {
            <<enumeration>>
        }
        class ChapterMarkers["ChapterMarkers"] {
            <<data class>>
        }
        class ChapterWriteResult["ChapterWriteResult"] {
            <<sealed interface>>
        }
        class ClipEdit["ClipEdit"] {
            <<data class>>
        }
        class ClipLook["ClipLook"] {
            <<enumeration>>
        }
        class DriftStop["DriftStop"] {
            <<data class>>
        }
        class EncoderSurface["EncoderSurface"] {
            <<class>>
        }
        class ExportAspect["ExportAspect"] {
            <<class>>
        }
        class ExportPreset["ExportPreset"] {
            <<data class>>
        }
        class ExportPresets["ExportPresets"] {
            <<object>>
        }
        class ExportQuality["ExportQuality"] {
            <<enumeration>>
        }
        class ExportRange["ExportRange"] {
            <<data class>>
        }
        class ExportRatio["ExportRatio"] {
            <<enumeration>>
        }
        class ExportRun["ExportRun"] {
            <<object>>
        }
        class ExportService["ExportService"] {
            <<class>>
        }
        class GainDirection["GainDirection"] {
            <<enumeration>>
        }
        class IntegratedLoudness["IntegratedLoudness"] {
            <<sealed interface>>
        }
        class LongFormAudio["LongFormAudio"] {
            <<sealed interface>>
        }
        class LoopExtend["LoopExtend"] {
            <<class>>
        }
        class LoopExtendPlan["LoopExtendPlan"] {
            <<data class>>
        }
        class LoopReel["LoopReel"] {
            <<class>>
        }
        class LoopRender["LoopRender"] {
            <<class>>
        }
        class LoopSegment["LoopSegment"] {
            <<data class>>
        }
        class LoopSpec["LoopSpec"] {
            <<data class>>
        }
        class LoopWrite["LoopWrite"] {
            <<data class>>
        }
        class LoudnessAdvice["LoudnessAdvice"] {
            <<sealed interface>>
        }
        class LoudnessAnalyser["LoudnessAnalyser"] {
            <<class>>
        }
        class LoudnessMeter["LoudnessMeter"] {
            <<class>>
        }
        class LoudnessReport["LoudnessReport"] {
            <<data class>>
        }
        class LoudnessResult["LoudnessResult"] {
            <<sealed interface>>
        }
        class LoudnessTarget["LoudnessTarget"] {
            <<sealed interface>>
        }
        class LoudnessTargets["LoudnessTargets"] {
            <<object>>
        }
        class LoudnessWindow["LoudnessWindow"] {
            <<data class>>
        }
        class MixClip["MixClip"] {
            <<data class>>
        }
        class MixClipSpan["MixClipSpan"] {
            <<data class>>
        }
        class RenderEta["RenderEta"] {
            <<class>>
        }
        class RenderedLoop["RenderedLoop"] {
            <<class>>
        }
        class StudioClip["StudioClip"] {
            <<data class>>
        }
        class StudioClips["StudioClips"] {
            <<object>>
        }
        class StudioExporter["StudioExporter"] {
            <<class>>
        }
        class TimeOfDayDrift["TimeOfDayDrift"] {
            <<data class>>
        }
        class VideoExporter["VideoExporter"] {
            <<class>>
        }
        class AudioTranscoder_Result["AudioTranscoder.Result"] {
            <<class>>
        }
        class AudioTranscoder_SampleInfo["AudioTranscoder.SampleInfo"] {
            <<class>>
        }
        class ChapterWriteResult_Failed["ChapterWriteResult.Failed"] {
            <<data class>>
        }
        class ChapterWriteResult_Skipped["ChapterWriteResult.Skipped"] {
            <<data object>>
        }
        class ChapterWriteResult_Written["ChapterWriteResult.Written"] {
            <<data object>>
        }
        class ExportRun_State["ExportRun.State"] {
            <<data class>>
        }
        class IntegratedLoudness_BelowGate["IntegratedLoudness.BelowGate"] {
            <<data object>>
        }
        class IntegratedLoudness_Lufs["IntegratedLoudness.Lufs"] {
            <<data class>>
        }
        class LongFormAudio_Mix["LongFormAudio.Mix"] {
            <<data class>>
        }
        class LongFormAudio_SingleTrack["LongFormAudio.SingleTrack"] {
            <<data class>>
        }
        class LoopExtend_AudioBuild["LoopExtend.AudioBuild"] {
            <<sealed interface>>
        }
        class LoopExtend_AudioReel["LoopExtend.AudioReel"] {
            <<class>>
        }
        class LoopExtend_LoopReader["LoopExtend.LoopReader"] {
            <<class>>
        }
        class LoopExtend_Result["LoopExtend.Result"] {
            <<sealed interface>>
        }
        class LoopExtend_Soundtrack["LoopExtend.Soundtrack"] {
            <<class>>
        }
        class LoopExtend_Target["LoopExtend.Target"] {
            <<sealed interface>>
        }
        class LoopExtend_TranscodedClip["LoopExtend.TranscodedClip"] {
            <<class>>
        }
        class LoopRender_EncoderChoice["LoopRender.EncoderChoice"] {
            <<data class>>
        }
        class LoopRender_RenderJob["LoopRender.RenderJob"] {
            <<class>>
        }
        class LoopRender_Result["LoopRender.Result"] {
            <<sealed interface>>
        }
        class LoopRender_SeamStash["LoopRender.SeamStash"] {
            <<class>>
        }
        class LoopRender_StopOutcome["LoopRender.StopOutcome"] {
            <<sealed interface>>
        }
        class LoopRender_StopWriter["LoopRender.StopWriter"] {
            <<class>>
        }
        class LoudnessAdvice_AsMixed["LoudnessAdvice.AsMixed"] {
            <<data class>>
        }
        class LoudnessAdvice_Normalise["LoudnessAdvice.Normalise"] {
            <<data class>>
        }
        class LoudnessAdvice_NothingToMeasure["LoudnessAdvice.NothingToMeasure"] {
            <<data object>>
        }
        class LoudnessAdvice_OnTarget["LoudnessAdvice.OnTarget"] {
            <<data class>>
        }
        class LoudnessAnalyser_Biquad["LoudnessAnalyser.Biquad"] {
            <<class>>
        }
        class LoudnessAnalyser_Gated["LoudnessAnalyser.Gated"] {
            <<class>>
        }
        class LoudnessAnalyser_TruePeakOversampler["LoudnessAnalyser.TruePeakOversampler"] {
            <<class>>
        }
        class LoudnessMeter_PcmLayout["LoudnessMeter.PcmLayout"] {
            <<sealed interface>>
        }
        class LoudnessMeter_Scratch["LoudnessMeter.Scratch"] {
            <<class>>
        }
        class LoudnessResult_Cancelled["LoudnessResult.Cancelled"] {
            <<data object>>
        }
        class LoudnessResult_Measured["LoudnessResult.Measured"] {
            <<data class>>
        }
        class LoudnessResult_NoAudioTrack["LoudnessResult.NoAudioTrack"] {
            <<data object>>
        }
        class LoudnessResult_TooShort["LoudnessResult.TooShort"] {
            <<data object>>
        }
        class LoudnessResult_Unreadable["LoudnessResult.Unreadable"] {
            <<data class>>
        }
        class LoudnessTarget_LeaveAsIs["LoudnessTarget.LeaveAsIs"] {
            <<data object>>
        }
        class LoudnessTarget_Normalising["LoudnessTarget.Normalising"] {
            <<sealed interface>>
        }
        class LoudnessTarget_ShortsAndTikTok["LoudnessTarget.ShortsAndTikTok"] {
            <<data object>>
        }
        class LoudnessTarget_YouTube["LoudnessTarget.YouTube"] {
            <<data object>>
        }
        class StudioExporter_Result["StudioExporter.Result"] {
            <<sealed interface>>
        }
        class VideoExporter_AudioFeed["VideoExporter.AudioFeed"] {
            <<class>>
        }
        class VideoExporter_Result["VideoExporter.Result"] {
            <<sealed interface>>
        }
        class LoopExtend_AudioBuild_Failed["LoopExtend.AudioBuild.Failed"] {
            <<data class>>
        }
        class LoopExtend_AudioBuild_Ready["LoopExtend.AudioBuild.Ready"] {
            <<data class>>
        }
        class LoopExtend_Result_Cancelled["LoopExtend.Result.Cancelled"] {
            <<data object>>
        }
        class LoopExtend_Result_Failed["LoopExtend.Result.Failed"] {
            <<data class>>
        }
        class LoopExtend_Result_Saved["LoopExtend.Result.Saved"] {
            <<data class>>
        }
        class LoopExtend_Target_Failed["LoopExtend.Target.Failed"] {
            <<class>>
        }
        class LoopExtend_Target_Opened["LoopExtend.Target.Opened"] {
            <<class>>
        }
        class LoopRender_Result_Cancelled["LoopRender.Result.Cancelled"] {
            <<data object>>
        }
        class LoopRender_Result_Failed["LoopRender.Result.Failed"] {
            <<data class>>
        }
        class LoopRender_Result_Rendered["LoopRender.Result.Rendered"] {
            <<data class>>
        }
        class LoopRender_StopOutcome_Cancelled["LoopRender.StopOutcome.Cancelled"] {
            <<data object>>
        }
        class LoopRender_StopOutcome_Done["LoopRender.StopOutcome.Done"] {
            <<data class>>
        }
        class LoudnessMeter_PcmLayout_Float32["LoudnessMeter.PcmLayout.Float32"] {
            <<data object>>
        }
        class LoudnessMeter_PcmLayout_Signed16["LoudnessMeter.PcmLayout.Signed16"] {
            <<data object>>
        }
        class LoudnessMeter_PcmLayout_Unsupported["LoudnessMeter.PcmLayout.Unsupported"] {
            <<data class>>
        }
        class StudioExporter_Result_Cancelled["StudioExporter.Result.Cancelled"] {
            <<data object>>
        }
        class StudioExporter_Result_Failed["StudioExporter.Result.Failed"] {
            <<data class>>
        }
        class StudioExporter_Result_Saved["StudioExporter.Result.Saved"] {
            <<data class>>
        }
        class VideoExporter_Result_Cancelled["VideoExporter.Result.Cancelled"] {
            <<data object>>
        }
        class VideoExporter_Result_Failed["VideoExporter.Result.Failed"] {
            <<data class>>
        }
        class VideoExporter_Result_Saved["VideoExporter.Result.Saved"] {
            <<data class>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_ui["~.ui"] {
            117 types
        }
        class pkg_app_dev_geode_editor["~.editor"] {
            98 types
        }
        class pkg_app_dev_geode_data["~.data"] {
            100 types
        }
        class pkg_scenes_dev_geode_render["~.render"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_scene["~.render.scene"] {
            49 types
        }
        class pkg_scenes_dev_geode_analysis["~.analysis"] {
            22 types
        }
    }

    namespace java.io {
        class ext_Closeable["Closeable"] {
            <<interface>>
        }
    }

    namespace android.app {
        class ext_Service["Service"] {
            <<class>>
        }
    }

    ChapterWriteResult <|.. ChapterWriteResult_Failed
    ChapterWriteResult <|.. ChapterWriteResult_Skipped
    ChapterWriteResult <|.. ChapterWriteResult_Written
    IntegratedLoudness <|.. IntegratedLoudness_BelowGate
    IntegratedLoudness <|.. IntegratedLoudness_Lufs
    LongFormAudio <|.. LongFormAudio_Mix
    LongFormAudio <|.. LongFormAudio_SingleTrack
    LoopExtend_AudioBuild <|.. LoopExtend_AudioBuild_Failed
    LoopExtend_AudioBuild <|.. LoopExtend_AudioBuild_Ready
    LoopExtend_Result <|.. LoopExtend_Result_Cancelled
    LoopExtend_Result <|.. LoopExtend_Result_Failed
    LoopExtend_Result <|.. LoopExtend_Result_Saved
    LoopExtend_Target <|.. LoopExtend_Target_Failed
    LoopExtend_Target <|.. LoopExtend_Target_Opened
    LoopRender_Result <|.. LoopRender_Result_Cancelled
    LoopRender_Result <|.. LoopRender_Result_Failed
    LoopRender_Result <|.. LoopRender_Result_Rendered
    LoopRender_StopOutcome <|.. LoopRender_StopOutcome_Cancelled
    LoopRender_StopOutcome <|.. LoopRender_StopOutcome_Done
    LoudnessAdvice <|.. LoudnessAdvice_AsMixed
    LoudnessAdvice <|.. LoudnessAdvice_Normalise
    LoudnessAdvice <|.. LoudnessAdvice_NothingToMeasure
    LoudnessAdvice <|.. LoudnessAdvice_OnTarget
    LoudnessMeter_PcmLayout <|.. LoudnessMeter_PcmLayout_Float32
    LoudnessMeter_PcmLayout <|.. LoudnessMeter_PcmLayout_Signed16
    LoudnessMeter_PcmLayout <|.. LoudnessMeter_PcmLayout_Unsupported
    LoudnessResult <|.. LoudnessResult_Cancelled
    LoudnessResult <|.. LoudnessResult_Measured
    LoudnessResult <|.. LoudnessResult_NoAudioTrack
    LoudnessResult <|.. LoudnessResult_TooShort
    LoudnessResult <|.. LoudnessResult_Unreadable
    LoudnessTarget <|.. LoudnessTarget_LeaveAsIs
    LoudnessTarget <|-- LoudnessTarget_Normalising
    LoudnessTarget_Normalising <|.. LoudnessTarget_ShortsAndTikTok
    LoudnessTarget_Normalising <|.. LoudnessTarget_YouTube
    StudioExporter_Result <|.. StudioExporter_Result_Cancelled
    StudioExporter_Result <|.. StudioExporter_Result_Failed
    StudioExporter_Result <|.. StudioExporter_Result_Saved
    VideoExporter_Result <|.. VideoExporter_Result_Cancelled
    VideoExporter_Result <|.. VideoExporter_Result_Failed
    VideoExporter_Result <|.. VideoExporter_Result_Saved
    AudioTranscoder --> AudioTranscoder_SampleInfo
    AudioTranscoder_Result --> AudioTranscoder_SampleInfo
    ChapterMarkers --> Chapter
    ClipEdit --> ClipLook
    ClipEdit --> ExportQuality
    ClipEdit --> ExportRatio
    ExportPreset --> ExportQuality
    ExportPreset --> ExportRatio
    ExportPresets --> ExportPreset
    LongFormAudio --> MixClip
    LongFormAudio_Mix --> MixClip
    LongFormAudio_SingleTrack --> MixClip
    LoopExtend --> AudioTranscoder
    LoopExtend --> LoopExtend_TranscodedClip
    LoopExtend --> MixClip
    LoopExtend_AudioBuild --> LoopExtend_Soundtrack
    LoopExtend_AudioBuild_Ready --> LoopExtend_Soundtrack
    LoopExtend_AudioReel --> LoopExtend_TranscodedClip
    LoopExtend_Result --> ChapterMarkers
    LoopExtend_Result --> LoopExtendPlan
    LoopExtend_Result_Saved --> ChapterMarkers
    LoopExtend_Result_Saved --> LoopExtendPlan
    LoopExtend_Soundtrack --> LoopExtend_TranscodedClip
    LoopExtend_Soundtrack --> MixClipSpan
    LoopExtend_TranscodedClip --> AudioTranscoder
    LoopExtend_TranscodedClip --> LoopExtend_Result
    LoopExtend_TranscodedClip --> MixClip
    LoopExtendPlan --> LoopSegment
    LoopExtendPlan --> LoopWrite
    LoopReel --> LoopSpec
    LoopReel --> RenderedLoop
    LoopRender --> ExportAspect
    LoopRender --> LoopRender_EncoderChoice
    LoopRender --> LoopSpec
    LoopRender --> TimeOfDayDrift
    LoopRender_RenderJob --> ExportAspect
    LoopRender_RenderJob --> LoopRender_EncoderChoice
    LoopRender_RenderJob --> LoopSpec
    LoopRender_RenderJob --> TimeOfDayDrift
    LoopRender_Result --> LoopReel
    LoopRender_Result_Rendered --> LoopReel
    LoudnessAdvice --> LoudnessTarget
    LoudnessAdvice --> LoudnessTarget_Normalising
    LoudnessAdvice_Normalise --> GainDirection
    LoudnessAdvice_Normalise --> LoudnessTarget
    LoudnessAdvice_Normalise --> LoudnessTarget_Normalising
    LoudnessAdvice_OnTarget --> LoudnessTarget
    LoudnessAdvice_OnTarget --> LoudnessTarget_Normalising
    LoudnessAnalyser --> IntegratedLoudness
    LoudnessAnalyser --> LoudnessAnalyser_Biquad
    LoudnessAnalyser_Gated --> IntegratedLoudness
    LoudnessReport --> IntegratedLoudness
    LoudnessResult --> LoudnessReport
    LoudnessResult_Measured --> LoudnessReport
    LoudnessTarget_Normalising --> LoudnessWindow
    LoudnessTarget_ShortsAndTikTok --> LoudnessWindow
    LoudnessTarget_YouTube --> LoudnessWindow
    MixClipSpan --> MixClip
    RenderedLoop --> DriftStop
    VideoExporter --> AudioTranscoder
    VideoExporter_AudioFeed --> AudioTranscoder
    VideoExporter_AudioFeed --> VideoExporter_Result
    ChapterMarkers ..> ChapterFormat
    ClipLook ..> ClipEdit
    ExportService ..> ExportRun
    LoopExtend ..> LoopExtend_AudioBuild
    LoopExtend ..> LoopExtend_LoopReader
    LoopExtend ..> LoopExtend_Target
    LoopExtend ..> LoopExtend_Target_Opened
    LoudnessAnalyser ..> LoudnessAnalyser_Gated
    LoudnessAnalyser ..> LoudnessReport
    StudioClips ..> StudioClip
    TimeOfDayDrift ..> DriftStop
    pkg_app_dev_geode_data ..> ExportQuality : 2
    pkg_app_dev_geode_data ..> ExportRatio : 2
    pkg_app_dev_geode_editor ..> ClipEdit : 2
    LoopRender ..> pkg_scenes_dev_geode_analysis
    LoopRender ..> pkg_scenes_dev_geode_render : 3
    LoopRender ..> pkg_scenes_dev_geode_render_scene
    LoopRender_RenderJob ..> pkg_scenes_dev_geode_analysis
    LoopRender_RenderJob ..> pkg_scenes_dev_geode_render : 3
    LoopRender_RenderJob ..> pkg_scenes_dev_geode_render_scene
    pkg_app_dev_geode_ui ..> ExportAspect
    pkg_app_dev_geode_ui ..> ExportRange
    pkg_app_dev_geode_ui ..> StudioClip
    DriftStop ..> pkg_scenes_dev_geode_render_scene
    ext_Service <|-- ExportService
    ext_Closeable <|.. LoopExtend_AudioReel
    ext_Closeable <|.. LoopRender_SeamStash
    ext_Closeable <|.. VideoExporter_AudioFeed
```

## app :: dev.geode.publish

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.publish {
        class ChapterCheck["ChapterCheck"] {
            <<sealed interface>>
        }
        class ChapterProblem["ChapterProblem"] {
            <<enumeration>>
        }
        class DescriptionMaker["DescriptionMaker"] {
            <<object>>
        }
        class PublishDescription["PublishDescription"] {
            <<data class>>
        }
        class PublishFacts["PublishFacts"] {
            <<data class>>
        }
        class PublishTemplateStore["PublishTemplateStore"] {
            <<class>>
        }
        class PublishTemplates["PublishTemplates"] {
            <<data class>>
        }
        class PublishToken["PublishToken"] {
            <<enumeration>>
        }
        class PublishTrack["PublishTrack"] {
            <<data class>>
        }
        class ThumbnailFormat["ThumbnailFormat"] {
            <<enumeration>>
        }
        class ThumbnailFrame["ThumbnailFrame"] {
            <<sealed interface>>
        }
        class ThumbnailLayout["ThumbnailLayout"] {
            <<enumeration>>
        }
        class ThumbnailMaker["ThumbnailMaker"] {
            <<class>>
        }
        class ThumbnailSave["ThumbnailSave"] {
            <<sealed interface>>
        }
        class ThumbnailSpec["ThumbnailSpec"] {
            <<data class>>
        }
        class ChapterCheck_NotChapters["ChapterCheck.NotChapters"] {
            <<data class>>
        }
        class ChapterCheck_Ready["ChapterCheck.Ready"] {
            <<data object>>
        }
        class ThumbnailFrame_NoFrameThere["ThumbnailFrame.NoFrameThere"] {
            <<data object>>
        }
        class ThumbnailFrame_Rendered["ThumbnailFrame.Rendered"] {
            <<data class>>
        }
        class ThumbnailFrame_Unreadable["ThumbnailFrame.Unreadable"] {
            <<data class>>
        }
        class ThumbnailSave_Failed["ThumbnailSave.Failed"] {
            <<data class>>
        }
        class ThumbnailSave_NoFrameThere["ThumbnailSave.NoFrameThere"] {
            <<data object>>
        }
        class ThumbnailSave_Saved["ThumbnailSave.Saved"] {
            <<data class>>
        }
    }

    ChapterCheck <|.. ChapterCheck_NotChapters
    ChapterCheck <|.. ChapterCheck_Ready
    ThumbnailFrame <|.. ThumbnailFrame_NoFrameThere
    ThumbnailFrame <|.. ThumbnailFrame_Rendered
    ThumbnailFrame <|.. ThumbnailFrame_Unreadable
    ThumbnailSave <|.. ThumbnailSave_Failed
    ThumbnailSave <|.. ThumbnailSave_NoFrameThere
    ThumbnailSave <|.. ThumbnailSave_Saved
    ChapterCheck --> ChapterProblem
    ChapterCheck_NotChapters --> ChapterProblem
    PublishDescription --> ChapterCheck
    PublishFacts --> PublishTrack
    ThumbnailSpec --> ThumbnailFormat
    ThumbnailSpec --> ThumbnailLayout
    ThumbnailSpec --> ThumbnailMaker
    DescriptionMaker ..> ChapterCheck
    DescriptionMaker ..> PublishFacts
    DescriptionMaker ..> PublishTrack
    PublishTemplateStore ..> PublishTemplates
    PublishToken ..> PublishFacts
    ThumbnailMaker ..> ThumbnailFrame
    ThumbnailMaker ..> ThumbnailSpec
```

## app :: dev.geode.data

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.data {
        class ArtworkStyle["ArtworkStyle"] {
            <<enumeration>>
        }
        class AtomicWrite["AtomicWrite"] {
            <<object>>
        }
        class AudioHandling["AudioHandling"] {
            <<enumeration>>
        }
        class BootAnimationStore["BootAnimationStore"] {
            <<class>>
        }
        class BundledTemplates["BundledTemplates"] {
            <<object>>
        }
        class CustomPalette["CustomPalette"] {
            <<data class>>
        }
        class ExportDefaults["ExportDefaults"] {
            <<data class>>
        }
        class ExportPrefsStore["ExportPrefsStore"] {
            <<class>>
        }
        class FavouritesRepository["FavouritesRepository"] {
            <<interface>>
        }
        class FavouritesStore["FavouritesStore"] {
            <<class>>
        }
        class FilePresetRepository["FilePresetRepository"] {
            <<class>>
        }
        class FileSessionRepository["FileSessionRepository"] {
            <<class>>
        }
        class FileTakeRepository["FileTakeRepository"] {
            <<class>>
        }
        class FileTemplateRepository["FileTemplateRepository"] {
            <<class>>
        }
        class ForeignFields["ForeignFields"] {
            <<class>>
        }
        class GeodePrefsFiles["GeodePrefsFiles"] {
            <<class>>
        }
        class HistoryStore["HistoryStore"] {
            <<class>>
        }
        class LfoStore["LfoStore"] {
            <<class>>
        }
        class MilkPackImporter["MilkPackImporter"] {
            <<object>>
        }
        class MilkTexture["MilkTexture"] {
            <<data class>>
        }
        class MilkTextureLink["MilkTextureLink"] {
            <<data class>>
        }
        class MilkTextureLinkKind["MilkTextureLinkKind"] {
            <<enumeration>>
        }
        class MilkTextureLinks["MilkTextureLinks"] {
            <<class>>
        }
        class MusicPlaylist["MusicPlaylist"] {
            <<data class>>
        }
        class MusicPlaylistStore["MusicPlaylistStore"] {
            <<class>>
        }
        class PaletteStore["PaletteStore"] {
            <<class>>
        }
        class PerformanceTake["PerformanceTake"] {
            <<object>>
        }
        class PlayerPrefs["PlayerPrefs"] {
            <<data class>>
        }
        class PlayerPrefsRepository["PlayerPrefsRepository"] {
            <<interface>>
        }
        class PlayerPrefsStore["PlayerPrefsStore"] {
            <<class>>
        }
        class PlaylistEntry["PlaylistEntry"] {
            <<data class>>
        }
        class PlaylistFormat["PlaylistFormat"] {
            <<enumeration>>
        }
        class PlaylistFormats["PlaylistFormats"] {
            <<object>>
        }
        class PlaylistParse["PlaylistParse"] {
            <<sealed interface>>
        }
        class PlaylistResolution["PlaylistResolution"] {
            <<data class>>
        }
        class Preset["Preset"] {
            <<data class>>
        }
        class PresetFolders["PresetFolders"] {
            <<data class>>
        }
        class PresetRepository["PresetRepository"] {
            <<interface>>
        }
        class PresetStore["PresetStore"] {
            <<class>>
        }
        class ProgressStyle["ProgressStyle"] {
            <<enumeration>>
        }
        class ResolvedText["ResolvedText"] {
            <<data class>>
        }
        class SessionRepository["SessionRepository"] {
            <<interface>>
        }
        class SessionStore["SessionStore"] {
            <<class>>
        }
        class SharedPrefsFavouritesRepository["SharedPrefsFavouritesRepository"] {
            <<class>>
        }
        class SharedPrefsPlayerPrefsRepository["SharedPrefsPlayerPrefsRepository"] {
            <<class>>
        }
        class TakeInfo["TakeInfo"] {
            <<data class>>
        }
        class TakeRepository["TakeRepository"] {
            <<interface>>
        }
        class TakeStore["TakeStore"] {
            <<class>>
        }
        class TemplateExport["TemplateExport"] {
            <<data class>>
        }
        class TemplateFormat["TemplateFormat"] {
            <<object>>
        }
        class TemplateId["TemplateId"] {
            <<value class>>
        }
        class TemplateImport["TemplateImport"] {
            <<sealed interface>>
        }
        class TemplateJob["TemplateJob"] {
            <<data class>>
        }
        class TemplateLayout["TemplateLayout"] {
            <<data class>>
        }
        class TemplateLook["TemplateLook"] {
            <<data class>>
        }
        class TemplateOrigin["TemplateOrigin"] {
            <<enumeration>>
        }
        class TemplateParse["TemplateParse"] {
            <<sealed interface>>
        }
        class TemplatePlaceholder["TemplatePlaceholder"] {
            <<enumeration>>
        }
        class TemplateRepository["TemplateRepository"] {
            <<interface>>
        }
        class TemplateSegment["TemplateSegment"] {
            <<sealed interface>>
        }
        class TemplateStore["TemplateStore"] {
            <<class>>
        }
        class TemplateText["TemplateText"] {
            <<data class>>
        }
        class TemplateWindow["TemplateWindow"] {
            <<data class>>
        }
        class TemplateWrite["TemplateWrite"] {
            <<sealed interface>>
        }
        class TextAnchor["TextAnchor"] {
            <<enumeration>>
        }
        class TextRole["TextRole"] {
            <<enumeration>>
        }
        class TextSlot["TextSlot"] {
            <<data class>>
        }
        class TextWeight["TextWeight"] {
            <<enumeration>>
        }
        class TextureImportOutcome["TextureImportOutcome"] {
            <<data class>>
        }
        class TextureImportResult["TextureImportResult"] {
            <<data class>>
        }
        class TextureRemoveOutcome["TextureRemoveOutcome"] {
            <<data class>>
        }
        class TextureStore["TextureStore"] {
            <<class>>
        }
        class Tolerant["Tolerant"] {
            <<sealed interface>>
        }
        class TrackFacts["TrackFacts"] {
            <<data class>>
        }
        class VideoTemplate["VideoTemplate"] {
            <<data class>>
        }
        class HistoryStore_Entry["HistoryStore.Entry"] {
            <<data class>>
        }
        class MilkPackImporter_Entry["MilkPackImporter.Entry"] {
            <<class>>
        }
        class MilkPackImporter_Report["MilkPackImporter.Report"] {
            <<data class>>
        }
        class PerformanceTake_Recorder["PerformanceTake.Recorder"] {
            <<class>>
        }
        class PerformanceTake_State["PerformanceTake.State"] {
            <<data class>>
        }
        class PerformanceTake_Timeline["PerformanceTake.Timeline"] {
            <<class>>
        }
        class PlaylistParse_Parsed["PlaylistParse.Parsed"] {
            <<data class>>
        }
        class PlaylistParse_Unreadable["PlaylistParse.Unreadable"] {
            <<data class>>
        }
        class SessionStore_Saved["SessionStore.Saved"] {
            <<data class>>
        }
        class SessionStore_SavedTrack["SessionStore.SavedTrack"] {
            <<data class>>
        }
        class TemplateImport_Added["TemplateImport.Added"] {
            <<data class>>
        }
        class TemplateImport_Replaced["TemplateImport.Replaced"] {
            <<data class>>
        }
        class TemplateImport_Unreadable["TemplateImport.Unreadable"] {
            <<data class>>
        }
        class TemplateImport_WriteFailed["TemplateImport.WriteFailed"] {
            <<data class>>
        }
        class TemplateParse_Malformed["TemplateParse.Malformed"] {
            <<data class>>
        }
        class TemplateParse_NotATemplate["TemplateParse.NotATemplate"] {
            <<data class>>
        }
        class TemplateParse_Parsed["TemplateParse.Parsed"] {
            <<data class>>
        }
        class TemplateSegment_Fixed["TemplateSegment.Fixed"] {
            <<data class>>
        }
        class TemplateSegment_LoudestWindow["TemplateSegment.LoudestWindow"] {
            <<data class>>
        }
        class TemplateSegment_Unknown["TemplateSegment.Unknown"] {
            <<data class>>
        }
        class TemplateSegment_WholeTrack["TemplateSegment.WholeTrack"] {
            <<data object>>
        }
        class TemplateWrite_Failed["TemplateWrite.Failed"] {
            <<data class>>
        }
        class TemplateWrite_Written["TemplateWrite.Written"] {
            <<data object>>
        }
        class Tolerant_Foreign["Tolerant.Foreign"] {
            <<data class>>
        }
        class Tolerant_Known["Tolerant.Known"] {
            <<data class>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_di["~.di"] {
            3 types
        }
        class pkg_app_dev_geode_ui["~.ui"] {
            117 types
        }
        class pkg_app_dev_geode_editor["~.editor"] {
            98 types
        }
        class pkg_app_dev_geode_export["~.export"] {
            109 types
        }
        class pkg_scenes_dev_geode_render["~.render"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_scene["~.render.scene"] {
            49 types
        }
    }

    PresetRepository <|.. FilePresetRepository
    SessionRepository <|.. FileSessionRepository
    TakeRepository <|.. FileTakeRepository
    TemplateRepository <|.. FileTemplateRepository
    PlaylistParse <|.. PlaylistParse_Parsed
    PlaylistParse <|.. PlaylistParse_Unreadable
    FavouritesRepository <|.. SharedPrefsFavouritesRepository
    PlayerPrefsRepository <|.. SharedPrefsPlayerPrefsRepository
    TemplateImport <|.. TemplateImport_Added
    TemplateImport <|.. TemplateImport_Replaced
    TemplateImport <|.. TemplateImport_Unreadable
    TemplateImport <|.. TemplateImport_WriteFailed
    TemplateParse <|.. TemplateParse_Malformed
    TemplateParse <|.. TemplateParse_NotATemplate
    TemplateParse <|.. TemplateParse_Parsed
    TemplateSegment <|.. TemplateSegment_Fixed
    TemplateSegment <|.. TemplateSegment_LoudestWindow
    TemplateSegment <|.. TemplateSegment_Unknown
    TemplateSegment <|.. TemplateSegment_WholeTrack
    TemplateWrite <|.. TemplateWrite_Failed
    TemplateWrite <|.. TemplateWrite_Written
    Tolerant <|.. Tolerant_Foreign
    Tolerant <|.. Tolerant_Known
    BundledTemplates --> VideoTemplate
    FilePresetRepository --> PresetFolders
    FilePresetRepository --> PresetStore
    FileSessionRepository --> SessionStore
    FileTakeRepository --> TakeStore
    FileTemplateRepository --> TemplateStore
    FileTemplateRepository --> VideoTemplate
    MilkTextureLink --> MilkTextureLinkKind
    PlayerPrefsRepository --> PlayerPrefs
    PlaylistEntry --> PlaylistFormats
    PlaylistParse --> PlaylistEntry
    PlaylistParse --> PlaylistFormat
    PlaylistParse_Parsed --> PlaylistEntry
    PlaylistParse_Parsed --> PlaylistFormat
    PlaylistResolution --> PlaylistEntry
    PresetRepository --> PresetFolders
    ResolvedText --> TextSlot
    SessionStore --> SessionStore_SavedTrack
    SessionStore_Saved --> SessionStore_SavedTrack
    SharedPrefsPlayerPrefsRepository --> PlayerPrefs
    SharedPrefsPlayerPrefsRepository --> PlayerPrefsStore
    TemplateExport --> AudioHandling
    TemplateExport --> ForeignFields
    TemplateExport --> TemplateSegment
    TemplateExport --> TemplateSegment_WholeTrack
    TemplateExport --> Tolerant
    TemplateExport --> Tolerant_Known
    TemplateImport --> VideoTemplate
    TemplateImport_Added --> VideoTemplate
    TemplateImport_Replaced --> VideoTemplate
    TemplateJob --> ResolvedText
    TemplateJob --> TemplateWindow
    TemplateJob --> TrackFacts
    TemplateJob --> VideoTemplate
    TemplateLayout --> ArtworkStyle
    TemplateLayout --> ForeignFields
    TemplateLayout --> ProgressStyle
    TemplateLayout --> Tolerant
    TemplateLayout --> Tolerant_Known
    TemplateLook --> ForeignFields
    TemplateParse --> VideoTemplate
    TemplateParse_Parsed --> VideoTemplate
    TemplateRepository --> VideoTemplate
    TemplateSegment --> ForeignFields
    TemplateSegment_Unknown --> ForeignFields
    TemplateText --> ForeignFields
    TemplateText --> TextSlot
    TextSlot --> ForeignFields
    TextSlot --> TextAnchor
    TextSlot --> TextRole
    TextSlot --> TextWeight
    TextSlot --> Tolerant
    TextSlot --> Tolerant_Known
    TextureImportOutcome --> MilkTexture
    TextureImportOutcome --> TextureImportResult
    TextureRemoveOutcome --> MilkTexture
    VideoTemplate --> ForeignFields
    VideoTemplate --> TemplateExport
    VideoTemplate --> TemplateFormat
    VideoTemplate --> TemplateId
    VideoTemplate --> TemplateLayout
    VideoTemplate --> TemplateLook
    VideoTemplate --> TemplateOrigin
    VideoTemplate --> TemplateText
    BundledTemplates ..> TemplateId
    ExportPrefsStore ..> ExportDefaults
    FilePresetRepository ..> Preset
    FileTakeRepository ..> PerformanceTake
    FileTakeRepository ..> TakeInfo
    FileTemplateRepository ..> TemplateFormat
    FileTemplateRepository ..> TemplateId
    FileTemplateRepository ..> TemplateImport
    FileTemplateRepository ..> TemplateWrite
    MilkTextureLinks ..> MilkTextureLink
    MusicPlaylistStore ..> MusicPlaylist
    MusicPlaylistStore ..> PresetStore
    PaletteStore ..> CustomPalette
    PerformanceTake_Timeline ..> PerformanceTake_State
    PlayerPrefsStore ..> PlayerPrefs
    PlaylistFormats ..> PlaylistEntry
    PlaylistFormats ..> PlaylistFormat
    PresetRepository ..> Preset
    PresetStore ..> Preset
    SessionRepository ..> SessionStore
    TakeRepository ..> PerformanceTake
    TakeRepository ..> TakeInfo
    TakeStore ..> PerformanceTake
    TakeStore ..> PresetStore
    TakeStore ..> TakeInfo
    TemplateFormat ..> ForeignFields
    TemplateFormat ..> TemplateExport
    TemplateFormat ..> TemplateLayout
    TemplateFormat ..> TemplateLook
    TemplateFormat ..> TemplateParse
    TemplateFormat ..> TemplateSegment
    TemplateFormat ..> TemplateText
    TemplateFormat ..> TextSlot
    TemplateFormat ..> Tolerant
    TemplateFormat ..> VideoTemplate
    TemplateLook ..> Preset
    TemplateRepository ..> TemplateId
    TemplateRepository ..> TemplateImport
    TemplateRepository ..> TemplateWrite
    TemplateStore ..> TemplateId
    TemplateStore ..> TemplateWrite
    TemplateStore ..> VideoTemplate
    TextureStore ..> MilkTexture
    TextureStore ..> TextureImportOutcome
    TextureStore ..> TextureImportResult
    TextureStore ..> TextureRemoveOutcome
    ExportDefaults ..> pkg_app_dev_geode_export : 2
    PerformanceTake ..> pkg_scenes_dev_geode_render_scene
    PerformanceTake_Recorder ..> pkg_scenes_dev_geode_render_scene
    PerformanceTake_State ..> pkg_scenes_dev_geode_render_scene
    Preset ..> pkg_scenes_dev_geode_render_scene
    TemplateExport ..> pkg_app_dev_geode_export
    TemplateLayout ..> pkg_app_dev_geode_export
    TemplateLook ..> pkg_scenes_dev_geode_render_scene : 2
    pkg_app_dev_geode_ui ..> Preset : 7
    pkg_app_dev_geode_ui ..> MusicPlaylist : 2
    pkg_app_dev_geode_ui ..> FavouritesRepository
    pkg_app_dev_geode_ui ..> SessionRepository
    pkg_app_dev_geode_ui ..> LfoStore
    pkg_app_dev_geode_ui ..> PlayerPrefs
    pkg_app_dev_geode_ui ..> PlayerPrefsRepository
    pkg_app_dev_geode_ui ..> PresetFolders
    pkg_app_dev_geode_ui ..> PresetRepository
    pkg_app_dev_geode_ui ..> CustomPalette
    pkg_app_dev_geode_ui ..> PaletteStore
    pkg_app_dev_geode_ui ..> PerformanceTake : 2
    pkg_app_dev_geode_ui ..> PerformanceTake_Recorder
    pkg_app_dev_geode_ui ..> TakeRepository
    pkg_app_dev_geode_ui ..> TakeInfo
    pkg_app_dev_geode_ui ..> MilkTexture
    FileTakeRepository ..> pkg_app_dev_geode_editor
    LfoStore ..> pkg_scenes_dev_geode_render : 3
    TakeRepository ..> pkg_app_dev_geode_editor
    TakeStore ..> pkg_app_dev_geode_editor
    TemplateFormat ..> pkg_app_dev_geode_ui
    pkg_app_dev_geode_di ..> GeodePrefsFiles
    pkg_app_dev_geode_ui ..> PresetStore
    pkg_app_dev_geode_ui ..> MilkTextureLink
```

## app :: dev.geode.playback

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.playback {
        class MediaArtwork["MediaArtwork"] {
            <<object>>
        }
        class PlaybackEngine["PlaybackEngine"] {
            <<object>>
        }
        class PlaybackErrors["PlaybackErrors"] {
            <<object>>
        }
        class PlaybackService["PlaybackService"] {
            <<class>>
        }
        class PlaybackSession["PlaybackSession"] {
            <<class>>
        }
        class QueueOps["QueueOps"] {
            <<object>>
        }
        class SessionBitmapLoader["SessionBitmapLoader"] {
            <<class>>
        }
        class ShuffleMode["ShuffleMode"] {
            <<sealed interface>>
        }
        class ShuffleModes["ShuffleModes"] {
            <<object>>
        }
        class ShuffleTrack["ShuffleTrack"] {
            <<data class>>
        }
        class SleepTimer["SleepTimer"] {
            <<class>>
        }
        class PlaybackErrors_Action["PlaybackErrors.Action"] {
            <<sealed interface>>
        }
        class PlaybackService_ResumptionCallback["PlaybackService.ResumptionCallback"] {
            <<class>>
        }
        class ShuffleMode_Albums["ShuffleMode.Albums"] {
            <<data object>>
        }
        class ShuffleMode_InOrder["ShuffleMode.InOrder"] {
            <<data object>>
        }
        class ShuffleMode_Spread["ShuffleMode.Spread"] {
            <<data object>>
        }
        class ShuffleMode_Tracks["ShuffleMode.Tracks"] {
            <<data object>>
        }
        class ShuffleMode_Weighted["ShuffleMode.Weighted"] {
            <<data object>>
        }
        class PlaybackErrors_Action_SkipToNext["PlaybackErrors.Action.SkipToNext"] {
            <<data object>>
        }
        class PlaybackErrors_Action_StopEndOfQueue["PlaybackErrors.Action.StopEndOfQueue"] {
            <<data object>>
        }
        class PlaybackErrors_Action_StopSourceUnavailable["PlaybackErrors.Action.StopSourceUnavailable"] {
            <<data object>>
        }
    }

    namespace androidx.media3.common {
        class ext_BitmapLoader["BitmapLoader"] {
            <<interface>>
        }
    }

    namespace androidx.media3.session {
        class ext_Callback["MediaSession.Callback"] {
            <<interface>>
        }
        class ext_MediaSessionService["MediaSessionService"] {
            <<class>>
        }
    }

    PlaybackErrors_Action <|.. PlaybackErrors_Action_SkipToNext
    PlaybackErrors_Action <|.. PlaybackErrors_Action_StopEndOfQueue
    PlaybackErrors_Action <|.. PlaybackErrors_Action_StopSourceUnavailable
    ShuffleMode <|.. ShuffleMode_Albums
    ShuffleMode <|.. ShuffleMode_InOrder
    ShuffleMode <|.. ShuffleMode_Spread
    ShuffleMode <|.. ShuffleMode_Tracks
    ShuffleMode <|.. ShuffleMode_Weighted
    PlaybackEngine --> PlaybackSession
    PlaybackService --> SessionBitmapLoader
    ext_MediaSessionService <|-- PlaybackService
    ext_Callback <|.. PlaybackService_ResumptionCallback
    ext_BitmapLoader <|.. SessionBitmapLoader
```

## app :: dev.geode.audio

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.audio {
        class AiffExtractor["AiffExtractor"] {
            <<class>>
        }
        class AudioBus["AudioBus"] {
            <<object>>
        }
        class AudioCapturePump["AudioCapturePump"] {
            <<abstract class>>
        }
        class AudioFxBand["AudioFxBand"] {
            <<data class>>
        }
        class AudioFxController["AudioFxController"] {
            <<class>>
        }
        class AudioFxFormat["AudioFxFormat"] {
            <<object>>
        }
        class AudioFxState["AudioFxState"] {
            <<data class>>
        }
        class CaptureFailure["CaptureFailure"] {
            <<enumeration>>
        }
        class GeodeNotificationListener["GeodeNotificationListener"] {
            <<class>>
        }
        class MediaProjectionHolder["MediaProjectionHolder"] {
            <<object>>
        }
        class MicCapture["MicCapture"] {
            <<class>>
        }
        class NowPlayingBridge["NowPlayingBridge"] {
            <<class>>
        }
        class PcmRingBuffer["PcmRingBuffer"] {
            <<class>>
        }
        class PcmTapSink["PcmTapSink"] {
            <<class>>
        }
        class PlaybackCapture["PlaybackCapture"] {
            <<class>>
        }
        class PlaybackCaptureService["PlaybackCaptureService"] {
            <<class>>
        }
        class TapRenderersFactory["TapRenderersFactory"] {
            <<class>>
        }
        class AiffExtractor_CommInfo["AiffExtractor.CommInfo"] {
            <<data class>>
        }
        class MicCapture_Failure["MicCapture.Failure"] {
            <<enumeration>>
        }
        class NowPlayingBridge_External["NowPlayingBridge.External"] {
            <<data class>>
        }
        class PlaybackCaptureService_IntentCompat["PlaybackCaptureService.IntentCompat"] {
            <<object>>
        }
    }

    namespace audio_core.dev.geode.engine.audio {
        class PcmSink["PcmSink"] {
            <<fun interface>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_ui["~.ui"] {
            117 types
        }
        class pkg_app_dev_geode_audio_dsp["~.audio.dsp"] {
            1 types
        }
        class pkg_scenes_dev_geode_analysis["~.analysis"] {
            22 types
        }
        class pkg_audio_android_dev_geode_engine_audioandroid["~.engine.audioandroid"] {
            9 types
        }
    }

    namespace android.app {
        class ext_Service["Service"] {
            <<class>>
        }
    }

    namespace android.service.notification {
        class ext_NotificationListenerService["NotificationListenerService"] {
            <<class>>
        }
    }

    namespace androidx.media3.exoplayer {
        class ext_AudioBufferSink["TeeAudioProcessor.AudioBufferSink"] {
            <<interface>>
        }
        class ext_DefaultRenderersFactory["DefaultRenderersFactory"] {
            <<class>>
        }
    }

    namespace androidx.media3.extractor {
        class ext_Extractor["Extractor"] {
            <<interface>>
        }
    }

    AudioCapturePump <|-- MicCapture
    PcmSink <|.. PcmRingBuffer
    AudioCapturePump <|-- PlaybackCapture
    AudioFxState --> AudioFxBand
    PcmTapSink --> PcmRingBuffer
    AudioFxController ..> AudioFxState
    MicCapture ..> MicCapture_Failure
    NowPlayingBridge ..> NowPlayingBridge_External
    AudioBus ..> pkg_scenes_dev_geode_analysis
    TapRenderersFactory ..> pkg_audio_android_dev_geode_engine_audioandroid : 2
    pkg_app_dev_geode_ui ..> CaptureFailure
    pkg_app_dev_geode_ui ..> NowPlayingBridge
    pkg_app_dev_geode_ui ..> NowPlayingBridge_External
    pkg_app_dev_geode_ui ..> MicCapture : 2
    pkg_app_dev_geode_ui ..> MicCapture_Failure : 2
    pkg_app_dev_geode_ui ..> AudioFxController
    pkg_app_dev_geode_ui ..> AudioFxState
    TapRenderersFactory ..> pkg_app_dev_geode_audio_dsp
    ext_Extractor <|.. AiffExtractor
    ext_NotificationListenerService <|-- GeodeNotificationListener
    ext_AudioBufferSink <|.. PcmTapSink
    ext_Service <|-- PlaybackCaptureService
    ext_DefaultRenderersFactory <|-- TapRenderersFactory
```

## app :: dev.geode.audio.dsp

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.audio.dsp {
        class MvzAudioProcessorChain["MvzAudioProcessorChain"] {
            <<class>>
        }
    }

    namespace audio_android.dev.geode.engine.audioandroid {
        class SkippedFrameSource["SkippedFrameSource"] {
            <<fun interface>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_audio["~.audio"] {
            21 types
        }
        class pkg_audio_android_dev_geode_engine_audioandroid["~.engine.audioandroid"] {
            9 types
        }
    }

    namespace androidx.media3.exoplayer {
        class ext_AudioProcessorChain["DefaultAudioSink.AudioProcessorChain"] {
            <<interface>>
        }
    }

    SkippedFrameSource <|.. MvzAudioProcessorChain
    MvzAudioProcessorChain ..> pkg_audio_android_dev_geode_engine_audioandroid : 2
    pkg_app_dev_geode_audio ..> MvzAudioProcessorChain
    ext_AudioProcessorChain <|.. MvzAudioProcessorChain
```

## app :: dev.geode.billing

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.billing {
        class AdMoment["AdMoment"] {
            <<sealed interface>>
        }
        class AdPolicy["AdPolicy"] {
            <<object>>
        }
        class BlockedReason["BlockedReason"] {
            <<sealed interface>>
        }
        class ExportGate["ExportGate"] {
            <<object>>
        }
        class ExportLimits["ExportLimits"] {
            <<data class>>
        }
        class ExportRequest["ExportRequest"] {
            <<data class>>
        }
        class ExportVerdict["ExportVerdict"] {
            <<sealed interface>>
        }
        class Tier["Tier"] {
            <<enumeration>>
        }
        class AdMoment_TrackFinished["AdMoment.TrackFinished"] {
            <<data object>>
        }
        class BlockedReason_TooLarge["BlockedReason.TooLarge"] {
            <<data class>>
        }
        class BlockedReason_TooLong["BlockedReason.TooLong"] {
            <<data class>>
        }
        class BlockedReason_TooLongAndTooLarge["BlockedReason.TooLongAndTooLarge"] {
            <<data class>>
        }
        class ExportVerdict_Allowed["ExportVerdict.Allowed"] {
            <<data object>>
        }
        class ExportVerdict_Blocked["ExportVerdict.Blocked"] {
            <<data class>>
        }
    }

    namespace other_packages {
        class pkg_scenes_dev_geode_render_fluid["~.render.fluid"] {
            34 types
        }
    }

    AdMoment <|.. AdMoment_TrackFinished
    BlockedReason <|.. BlockedReason_TooLarge
    BlockedReason <|.. BlockedReason_TooLong
    BlockedReason <|.. BlockedReason_TooLongAndTooLarge
    ExportVerdict <|.. ExportVerdict_Allowed
    ExportVerdict <|.. ExportVerdict_Blocked
    ExportVerdict --> BlockedReason
    ExportVerdict --> ExportRequest
    ExportVerdict_Blocked --> BlockedReason
    ExportVerdict_Blocked --> ExportRequest
    Tier --> ExportLimits
    pkg_scenes_dev_geode_render_fluid ..> Tier
```

## app :: dev.geode.wallpaper

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.wallpaper {
        class IdleFeatures["IdleFeatures"] {
            <<class>>
        }
        class VisualizerWallpaperService["VisualizerWallpaperService"] {
            <<class>>
        }
        class VisualizerWallpaperService_VisualizerEngine["VisualizerWallpaperService.VisualizerEngine"] {
            <<inner class>>
        }
        class VisualizerWallpaperService_VisualizerEngine_WallpaperGlSurfaceView["VisualizerWallpaperService.VisualizerEngine.WallpaperGlSurfaceView"] {
            <<inner class>>
        }
    }

    namespace other_packages {
        class pkg_scenes_dev_geode_render["~.render"] {
            49 types
        }
        class pkg_scenes_dev_geode_analysis["~.analysis"] {
            22 types
        }
    }

    namespace android.opengl {
        class ext_GLSurfaceView["GLSurfaceView"] {
            <<class>>
        }
    }

    namespace android.service.wallpaper {
        class ext_Engine["WallpaperService.Engine"] {
            <<class>>
        }
        class ext_WallpaperService["WallpaperService"] {
            <<class>>
        }
    }

    VisualizerWallpaperService_VisualizerEngine --> VisualizerWallpaperService_VisualizerEngine_WallpaperGlSurfaceView
    VisualizerWallpaperService ..> VisualizerWallpaperService_VisualizerEngine
    VisualizerWallpaperService_VisualizerEngine ..> pkg_scenes_dev_geode_render
    IdleFeatures ..> pkg_scenes_dev_geode_analysis
    ext_WallpaperService <|-- VisualizerWallpaperService
    ext_Engine <|-- VisualizerWallpaperService_VisualizerEngine
    ext_GLSurfaceView <|-- VisualizerWallpaperService_VisualizerEngine_WallpaperGlSurfaceView
```

## engine:scenes :: dev.geode.audio

```mermaid
classDiagram
    direction TB

    namespace scenes.dev.geode.audio {
        class AiffPcm["AiffPcm"] {
            <<class>>
        }
    }
```

## engine:scenes :: dev.geode.render

```mermaid
classDiagram
    direction TB

    namespace scenes.dev.geode.render {
        class AdsrConfig["AdsrConfig"] {
            <<data class>>
        }
        class AdsrEngine["AdsrEngine"] {
            <<class>>
        }
        class BlendMode["BlendMode"] {
            <<enumeration>>
        }
        class BlueNoise["BlueNoise"] {
            <<object>>
        }
        class CompositeGrade["CompositeGrade"] {
            <<object>>
        }
        class CompositePass["CompositePass"] {
            <<class>>
        }
        class CyclicPalettes["CyclicPalettes"] {
            <<object>>
        }
        class EnvBand["EnvBand"] {
            <<enumeration>>
        }
        class FlashBudget["FlashBudget"] {
            <<class>>
        }
        class FramePacer["FramePacer"] {
            <<class>>
        }
        class FrameRatePolicy["FrameRatePolicy"] {
            <<sealed interface>>
        }
        class FrameSink["FrameSink"] {
            <<fun interface>>
        }
        class FrameStats["FrameStats"] {
            <<data class>>
        }
        class LfoConfig["LfoConfig"] {
            <<data class>>
        }
        class LfoEngine["LfoEngine"] {
            <<class>>
        }
        class LfoTarget["LfoTarget"] {
            <<enumeration>>
        }
        class LfoWave["LfoWave"] {
            <<enumeration>>
        }
        class LiveSignal["LiveSignal"] {
            <<object>>
        }
        class ModChain["ModChain"] {
            <<data class>>
        }
        class ModChainField["ModChainField"] {
            <<enumeration>>
        }
        class ModCurve["ModCurve"] {
            <<enumeration>>
        }
        class ModPolarity["ModPolarity"] {
            <<enumeration>>
        }
        class ModSource["ModSource"] {
            <<enumeration>>
        }
        class OverlayEffects["OverlayEffects"] {
            <<class>>
        }
        class ParamBlend["ParamBlend"] {
            <<object>>
        }
        class RenderTarget["RenderTarget"] {
            <<class>>
        }
        class SceneFactory["SceneFactory"] {
            <<interface>>
        }
        class SceneRegistry["SceneRegistry"] {
            <<class>>
        }
        class ThermalGovernor["ThermalGovernor"] {
            <<object>>
        }
        class ThermalTier["ThermalTier"] {
            <<enumeration>>
        }
        class TouchField["TouchField"] {
            <<class>>
        }
        class TrailPass["TrailPass"] {
            <<class>>
        }
        class TransitionCatalog["TransitionCatalog"] {
            <<object>>
        }
        class TransitionPrograms["TransitionPrograms"] {
            <<class>>
        }
        class TransitionStyle["TransitionStyle"] {
            <<enumeration>>
        }
        class VisualSafety["VisualSafety"] {
            <<object>>
        }
        class VisualizerRenderer["VisualizerRenderer"] {
            <<class>>
        }
        class VisualizerView["VisualizerView"] {
            <<class>>
        }
        class CompositeGrade_Gate["CompositeGrade.Gate"] {
            <<data class>>
        }
        class CompositeGrade_SceneFamily["CompositeGrade.SceneFamily"] {
            <<enumeration>>
        }
        class CompositePass_Inputs["CompositePass.Inputs"] {
            <<class>>
        }
        class FrameRatePolicy_Capped["FrameRatePolicy.Capped"] {
            <<data class>>
        }
        class FrameRatePolicy_Native["FrameRatePolicy.Native"] {
            <<data object>>
        }
        class LiveSignal_Edge["LiveSignal.Edge"] {
            <<class>>
        }
        class LiveSignal_Traverse["LiveSignal.Traverse"] {
            <<class>>
        }
        class OverlayEffects_TouchStroke["OverlayEffects.TouchStroke"] {
            <<class>>
        }
        class SceneRegistry_Host["SceneRegistry.Host"] {
            <<interface>>
        }
        class TransitionCatalog_Def["TransitionCatalog.Def"] {
            <<data class>>
        }
        class TransitionCatalog_Param["TransitionCatalog.Param"] {
            <<data class>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_ui["~.ui"] {
            117 types
        }
        class pkg_app_dev_geode_export["~.export"] {
            109 types
        }
        class pkg_app_dev_geode_data["~.data"] {
            100 types
        }
        class pkg_app_dev_geode_wallpaper["~.wallpaper"] {
            4 types
        }
        class pkg_scenes_dev_geode_render_scene["~.render.scene"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_fluid["~.render.fluid"] {
            34 types
        }
        class pkg_scenes_dev_geode_render_offscreen["~.render.offscreen"] {
            6 types
        }
        class pkg_scenes_dev_geode_analysis["~.analysis"] {
            22 types
        }
        class pkg_gl_dev_geode_engine_gl["~.engine.gl"] {
            53 types
        }
    }

    namespace android.opengl {
        class ext_GLSurfaceView["GLSurfaceView"] {
            <<class>>
        }
        class ext_Renderer["GLSurfaceView.Renderer"] {
            <<interface>>
        }
    }

    FrameRatePolicy <|.. FrameRatePolicy_Capped
    FrameRatePolicy <|.. FrameRatePolicy_Native
    AdsrConfig --> EnvBand
    AdsrConfig --> LfoTarget
    AdsrEngine --> AdsrConfig
    CompositeGrade --> CompositeGrade_Gate
    CompositeGrade --> CompositeGrade_SceneFamily
    CompositePass_Inputs --> TransitionStyle
    FlashBudget --> VisualSafety
    FramePacer --> FrameRatePolicy
    FramePacer --> FrameRatePolicy_Capped
    FramePacer --> FrameSink
    LfoConfig --> LfoTarget
    LfoConfig --> LfoWave
    LfoConfig --> ModCurve
    LfoConfig --> ModPolarity
    LfoConfig --> ModSource
    LfoEngine --> LfoConfig
    LfoTarget --> ModChain
    ModChain --> ModChainField
    OverlayEffects --> TouchField
    SceneRegistry --> TouchField
    ThermalGovernor --> ThermalTier
    TransitionCatalog --> TransitionCatalog_Def
    TransitionCatalog --> TransitionCatalog_Param
    TransitionCatalog --> TransitionStyle
    TransitionCatalog_Def --> TransitionCatalog_Param
    TransitionPrograms --> TransitionCatalog
    TransitionPrograms --> TransitionCatalog_Def
    VisualizerRenderer --> BlendMode
    VisualizerRenderer --> TransitionStyle
    VisualizerView --> FramePacer
    VisualizerView --> VisualizerRenderer
    CompositePass ..> CompositePass_Inputs
    FramePacer ..> FrameStats
    VisualSafety ..> TransitionStyle
    VisualizerRenderer ..> CompositeGrade
    VisualizerRenderer ..> CompositeGrade_SceneFamily
    VisualizerRenderer ..> SceneFactory
    pkg_app_dev_geode_export ..> AdsrConfig : 2
    pkg_app_dev_geode_export ..> LfoConfig : 2
    pkg_app_dev_geode_export ..> SceneFactory : 2
    CompositePass_Inputs ..> pkg_scenes_dev_geode_render_scene
    LfoTarget ..> pkg_scenes_dev_geode_render_scene
    OverlayEffects ..> pkg_scenes_dev_geode_render_fluid : 2
    SceneRegistry ..> pkg_scenes_dev_geode_render_scene : 3
    TransitionPrograms ..> pkg_scenes_dev_geode_render_scene : 2
    VisualizerRenderer ..> pkg_scenes_dev_geode_analysis
    VisualizerRenderer ..> pkg_gl_dev_geode_engine_gl
    VisualizerRenderer ..> pkg_scenes_dev_geode_render_scene : 4
    pkg_scenes_dev_geode_render_offscreen ..> AdsrConfig
    pkg_scenes_dev_geode_render_offscreen ..> LfoConfig
    pkg_scenes_dev_geode_render_offscreen ..> SceneFactory
    pkg_scenes_dev_geode_render_scene ..> TouchField : 8
    pkg_app_dev_geode_ui ..> BlendMode
    pkg_app_dev_geode_ui ..> AdsrConfig : 2
    pkg_app_dev_geode_ui ..> LfoConfig : 2
    pkg_app_dev_geode_ui ..> VisualizerRenderer
    pkg_app_dev_geode_ui ..> TransitionStyle : 2
    pkg_app_dev_geode_wallpaper ..> VisualizerRenderer
    pkg_app_dev_geode_data ..> AdsrConfig
    pkg_app_dev_geode_data ..> LfoConfig
    pkg_app_dev_geode_data ..> LfoEngine
    LiveSignal ..> pkg_scenes_dev_geode_analysis
    LiveSignal_Edge ..> pkg_scenes_dev_geode_analysis
    OverlayEffects ..> pkg_scenes_dev_geode_render_scene
    SceneFactory ..> pkg_scenes_dev_geode_render_scene
    ext_Renderer <|.. VisualizerRenderer
    ext_GLSurfaceView <|-- VisualizerView
```

## engine:scenes :: dev.geode.render.scene

```mermaid
classDiagram
    direction TB

    namespace scenes.dev.geode.render.scene {
        class AcidScene["AcidScene"] {
            <<class>>
        }
        class BeamScene["BeamScene"] {
            <<class>>
        }
        class CustomizeTab["CustomizeTab"] {
            <<enumeration>>
        }
        class CymaticsDrops["CymaticsDrops"] {
            <<class>>
        }
        class CymaticsMath["CymaticsMath"] {
            <<object>>
        }
        class CymaticsPlate["CymaticsPlate"] {
            <<class>>
        }
        class CymaticsScene["CymaticsScene"] {
            <<class>>
        }
        class GlUtil["GlUtil"] {
            <<object>>
        }
        class LifeScene["LifeScene"] {
            <<class>>
        }
        class MarchBudget["MarchBudget"] {
            <<value class>>
        }
        class MilkStarterPack["MilkStarterPack"] {
            <<object>>
        }
        class MycoScene["MycoScene"] {
            <<class>>
        }
        class ParamKeys["ParamKeys"] {
            <<object>>
        }
        class ParamRandomizer["ParamRandomizer"] {
            <<object>>
        }
        class ParamScope["ParamScope"] {
            <<enumeration>>
        }
        class ParticleLook["ParticleLook"] {
            <<object>>
        }
        class PcmChunk["PcmChunk"] {
            <<class>>
        }
        class PcmPulse["PcmPulse"] {
            <<class>>
        }
        class PcmRow["PcmRow"] {
            <<object>>
        }
        class scenes_render_scene_PcmSink["PcmSink"] {
            <<interface>>
        }
        class ProgramBinaryCache["ProgramBinaryCache"] {
            <<object>>
        }
        class ProgramKey["ProgramKey"] {
            <<value class>>
        }
        class ProjectMEngine["ProjectMEngine"] {
            <<object>>
        }
        class ProjectMScene["ProjectMScene"] {
            <<class>>
        }
        class Scene["Scene"] {
            <<interface>>
        }
        class SceneCapabilities["SceneCapabilities"] {
            <<object>>
        }
        class SceneIds["SceneIds"] {
            <<object>>
        }
        class SceneKind["SceneKind"] {
            <<enumeration>>
        }
        class SceneParams["SceneParams"] {
            <<data class>>
        }
        class SceneTouch["SceneTouch"] {
            <<object>>
        }
        class ShaderScene["ShaderScene"] {
            <<class>>
        }
        class SilkScene["SilkScene"] {
            <<class>>
        }
        class TouchReactive["TouchReactive"] {
            <<interface>>
        }
        class TouchTransform["TouchTransform"] {
            <<object>>
        }
        class VisualStyleCatalog["VisualStyleCatalog"] {
            <<object>>
        }
        class CymaticsMath_Mode["CymaticsMath.Mode"] {
            <<data class>>
        }
        class GlUtil_FullscreenTriangle["GlUtil.FullscreenTriangle"] {
            <<class>>
        }
        class GlUtil_ShaderCompileException["GlUtil.ShaderCompileException"] {
            <<class>>
        }
        class GlUtil_UniformCache["GlUtil.UniformCache"] {
            <<class>>
        }
        class ProgramBinaryCache_Entry["ProgramBinaryCache.Entry"] {
            <<class>>
        }
        class ProgramBinaryCache_Store["ProgramBinaryCache.Store"] {
            <<sealed interface>>
        }
        class VisualStyleCatalog_AcidStyle["VisualStyleCatalog.AcidStyle"] {
            <<data class>>
        }
        class VisualStyleCatalog_CymaticsStyle["VisualStyleCatalog.CymaticsStyle"] {
            <<data class>>
        }
        class VisualStyleCatalog_LifeStyle["VisualStyleCatalog.LifeStyle"] {
            <<data class>>
        }
        class VisualStyleCatalog_MycoStyle["VisualStyleCatalog.MycoStyle"] {
            <<data class>>
        }
        class VisualStyleCatalog_SilkStyle["VisualStyleCatalog.SilkStyle"] {
            <<data class>>
        }
        class ProgramBinaryCache_Store_Off["ProgramBinaryCache.Store.Off"] {
            <<data class>>
        }
        class ProgramBinaryCache_Store_On["ProgramBinaryCache.Store.On"] {
            <<class>>
        }
        class ProgramBinaryCache_Store_Unprimed["ProgramBinaryCache.Store.Unprimed"] {
            <<data object>>
        }
    }

    namespace scenes.dev.geode.render.fluid {
        class FluidSceneBase["FluidSceneBase"] {
            <<abstract class>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_ui["~.ui"] {
            117 types
        }
        class pkg_app_dev_geode_export["~.export"] {
            109 types
        }
        class pkg_app_dev_geode_data["~.data"] {
            100 types
        }
        class pkg_scenes_dev_geode_render["~.render"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_fluid["~.render.fluid"] {
            34 types
        }
        class pkg_scenes_dev_geode_render_compute["~.render.compute"] {
            15 types
        }
        class pkg_scenes_dev_geode_render_offscreen["~.render.offscreen"] {
            6 types
        }
        class pkg_scenes_dev_geode_analysis["~.analysis"] {
            22 types
        }
    }

    namespace kotlin {
        class ext_RuntimeException["RuntimeException"] {
            <<class>>
        }
    }

    scenes_render_scene_PcmSink <|.. FluidSceneBase
    Scene <|.. FluidSceneBase
    scenes_render_scene_PcmSink <|.. AcidScene
    Scene <|.. AcidScene
    TouchReactive <|.. AcidScene
    scenes_render_scene_PcmSink <|.. BeamScene
    Scene <|.. BeamScene
    TouchReactive <|.. BeamScene
    scenes_render_scene_PcmSink <|.. CymaticsScene
    Scene <|.. CymaticsScene
    TouchReactive <|.. CymaticsScene
    scenes_render_scene_PcmSink <|.. LifeScene
    Scene <|.. LifeScene
    TouchReactive <|.. LifeScene
    scenes_render_scene_PcmSink <|.. MycoScene
    Scene <|.. MycoScene
    TouchReactive <|.. MycoScene
    ProgramBinaryCache_Store <|.. ProgramBinaryCache_Store_Off
    ProgramBinaryCache_Store <|.. ProgramBinaryCache_Store_On
    ProgramBinaryCache_Store <|.. ProgramBinaryCache_Store_Unprimed
    scenes_render_scene_PcmSink <|.. ProjectMScene
    Scene <|.. ProjectMScene
    scenes_render_scene_PcmSink <|.. ShaderScene
    Scene <|.. ShaderScene
    scenes_render_scene_PcmSink <|.. SilkScene
    Scene <|.. SilkScene
    TouchReactive <|.. SilkScene
    AcidScene --> VisualStyleCatalog
    AcidScene --> VisualStyleCatalog_AcidStyle
    CymaticsMath --> CymaticsMath_Mode
    CymaticsScene --> VisualStyleCatalog
    CymaticsScene --> VisualStyleCatalog_CymaticsStyle
    LifeScene --> VisualStyleCatalog
    LifeScene --> VisualStyleCatalog_LifeStyle
    MycoScene --> VisualStyleCatalog
    MycoScene --> VisualStyleCatalog_MycoStyle
    ParamRandomizer --> CustomizeTab
    ProgramBinaryCache --> ProgramBinaryCache_Store
    ProjectMScene --> SceneParams
    ShaderScene --> SceneParams
    SilkScene --> VisualStyleCatalog
    SilkScene --> VisualStyleCatalog_SilkStyle
    VisualStyleCatalog --> VisualStyleCatalog_AcidStyle
    VisualStyleCatalog --> VisualStyleCatalog_CymaticsStyle
    VisualStyleCatalog --> VisualStyleCatalog_LifeStyle
    VisualStyleCatalog --> VisualStyleCatalog_MycoStyle
    VisualStyleCatalog --> VisualStyleCatalog_SilkStyle
    VisualStyleCatalog_CymaticsStyle --> CymaticsMath
    AcidScene ..> SceneParams
    BeamScene ..> SceneParams
    CymaticsScene ..> SceneParams
    LifeScene ..> SceneParams
    MycoScene ..> SceneParams
    ParamRandomizer ..> SceneParams
    ParamScope ..> SceneKind
    ProgramBinaryCache ..> ProgramBinaryCache_Store_On
    ProgramBinaryCache ..> ProgramKey
    Scene ..> SceneParams
    SceneCapabilities ..> SceneIds
    SceneCapabilities ..> SceneKind
    SceneCapabilities ..> VisualStyleCatalog
    SilkScene ..> SceneParams
    pkg_app_dev_geode_data ..> SceneParams : 5
    pkg_app_dev_geode_data ..> SceneIds
    pkg_app_dev_geode_export ..> SceneParams : 3
    pkg_scenes_dev_geode_render ..> SceneParams : 3
    pkg_scenes_dev_geode_render ..> ParamScope
    pkg_scenes_dev_geode_render ..> ProjectMScene
    pkg_scenes_dev_geode_render ..> GlUtil
    pkg_scenes_dev_geode_render ..> GlUtil_UniformCache
    pkg_scenes_dev_geode_render ..> PcmChunk
    pkg_scenes_dev_geode_render ..> Scene : 4
    pkg_scenes_dev_geode_render_fluid ..> GlUtil
    pkg_scenes_dev_geode_render_fluid ..> GlUtil_UniformCache
    pkg_scenes_dev_geode_render_offscreen ..> SceneParams
    pkg_scenes_dev_geode_render_offscreen ..> Scene
    AcidScene ..> pkg_scenes_dev_geode_analysis
    AcidScene ..> pkg_scenes_dev_geode_render
    AcidScene ..> pkg_scenes_dev_geode_render_fluid : 2
    BeamScene ..> pkg_scenes_dev_geode_render
    CymaticsScene ..> pkg_scenes_dev_geode_analysis
    CymaticsScene ..> pkg_scenes_dev_geode_render
    LifeScene ..> pkg_scenes_dev_geode_analysis
    LifeScene ..> pkg_scenes_dev_geode_render
    LifeScene ..> pkg_scenes_dev_geode_render_fluid : 3
    MycoScene ..> pkg_scenes_dev_geode_analysis
    MycoScene ..> pkg_scenes_dev_geode_render
    MycoScene ..> pkg_scenes_dev_geode_render_fluid : 3
    ShaderScene ..> pkg_scenes_dev_geode_render
    SilkScene ..> pkg_scenes_dev_geode_analysis
    SilkScene ..> pkg_scenes_dev_geode_render
    SilkScene ..> pkg_scenes_dev_geode_render_compute : 2
    pkg_app_dev_geode_ui ..> SceneParams : 11
    pkg_app_dev_geode_ui ..> SceneIds
    pkg_scenes_dev_geode_analysis ..> SceneParams
    pkg_scenes_dev_geode_render ..> scenes_render_scene_PcmSink
    pkg_scenes_dev_geode_render_fluid ..> SceneParams : 2
    TouchReactive ..> pkg_scenes_dev_geode_render
    pkg_app_dev_geode_ui ..> CustomizeTab
    pkg_app_dev_geode_ui ..> ParamRandomizer
    ext_RuntimeException <|-- GlUtil_ShaderCompileException
```

## engine:scenes :: dev.geode.render.fluid

```mermaid
classDiagram
    direction TB

    namespace scenes.dev.geode.render.scene {
        class scenes_render_scene_PcmSink["PcmSink"] {
            <<interface>>
        }
        class Scene["Scene"] {
            <<interface>>
        }
    }

    namespace scenes.dev.geode.render.fluid {
        class CurlFlowMath["CurlFlowMath"] {
            <<object>>
        }
        class CurlFlowScene["CurlFlowScene"] {
            <<class>>
        }
        class FlowField["FlowField"] {
            <<class>>
        }
        class FluidAudioDrive["FluidAudioDrive"] {
            <<class>>
        }
        class FluidBuffers["FluidBuffers"] {
            <<object>>
        }
        class FluidChoreography["FluidChoreography"] {
            <<class>>
        }
        class FluidEmitters["FluidEmitters"] {
            <<class>>
        }
        class FluidHue["FluidHue"] {
            <<object>>
        }
        class FluidLook["FluidLook"] {
            <<class>>
        }
        class FluidMath["FluidMath"] {
            <<object>>
        }
        class FluidParticles["FluidParticles"] {
            <<class>>
        }
        class FluidQuality["FluidQuality"] {
            <<object>>
        }
        class FluidScene["FluidScene"] {
            <<class>>
        }
        class FluidSceneBase["FluidSceneBase"] {
            <<abstract class>>
        }
        class FluidShaderTemplate["FluidShaderTemplate"] {
            <<object>>
        }
        class FluidSim["FluidSim"] {
            <<class>>
        }
        class PerformanceMonitor["PerformanceMonitor"] {
            <<class>>
        }
        class RippleMath["RippleMath"] {
            <<object>>
        }
        class RippleOverlayDrops["RippleOverlayDrops"] {
            <<class>>
        }
        class RippleSim["RippleSim"] {
            <<class>>
        }
        class WaterMath["WaterMath"] {
            <<object>>
        }
        class WaterScene["WaterScene"] {
            <<class>>
        }
        class FlowField_CpuGrid["FlowField.CpuGrid"] {
            <<class>>
        }
        class FluidBuffers_DoubleFbo["FluidBuffers.DoubleFbo"] {
            <<class>>
        }
        class FluidBuffers_DoubleMrt["FluidBuffers.DoubleMrt"] {
            <<class>>
        }
        class FluidBuffers_Fbo["FluidBuffers.Fbo"] {
            <<class>>
        }
        class FluidBuffers_Formats["FluidBuffers.Formats"] {
            <<data class>>
        }
        class FluidBuffers_TexFormat["FluidBuffers.TexFormat"] {
            <<data class>>
        }
        class FluidChoreography_Anchor["FluidChoreography.Anchor"] {
            <<class>>
        }
        class FluidQuality_Tier["FluidQuality.Tier"] {
            <<data class>>
        }
        class FluidSim_Splat["FluidSim.Splat"] {
            <<class>>
        }
        class RippleMath_StrokeDrop["RippleMath.StrokeDrop"] {
            <<data class>>
        }
        class RippleSim_Drop["RippleSim.Drop"] {
            <<class>>
        }
        class FluidBuffers_DoubleMrt_Side["FluidBuffers.DoubleMrt.Side"] {
            <<class>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_billing["~.billing"] {
            14 types
        }
        class pkg_scenes_dev_geode_render["~.render"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_scene["~.render.scene"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_offscreen["~.render.offscreen"] {
            6 types
        }
        class pkg_scenes_dev_geode_analysis["~.analysis"] {
            22 types
        }
    }

    FluidSceneBase <|-- CurlFlowScene
    FluidSceneBase <|-- FluidScene
    scenes_render_scene_PcmSink <|.. FluidSceneBase
    Scene <|.. FluidSceneBase
    FluidSceneBase <|-- WaterScene
    CurlFlowScene --> FluidBuffers
    CurlFlowScene --> FluidBuffers_Fbo
    CurlFlowScene --> FluidBuffers_Formats
    FluidBuffers --> FluidBuffers_TexFormat
    FluidBuffers_DoubleFbo --> FluidBuffers_TexFormat
    FluidBuffers_DoubleMrt --> FluidBuffers_TexFormat
    FluidBuffers_DoubleMrt_Side --> FluidBuffers_TexFormat
    FluidBuffers_Fbo --> FluidBuffers_TexFormat
    FluidBuffers_Formats --> FluidBuffers_TexFormat
    FluidChoreography --> FluidChoreography_Anchor
    FluidEmitters --> FluidChoreography
    FluidLook --> FluidBuffers
    FluidLook --> FluidBuffers_Fbo
    FluidLook --> FluidBuffers_Formats
    FluidParticles --> FluidBuffers
    FluidParticles --> FluidBuffers_DoubleMrt
    FluidSim --> FluidBuffers
    FluidSim --> FluidBuffers_DoubleFbo
    FluidSim --> FluidBuffers_Fbo
    FluidSim --> FluidBuffers_Formats
    RippleSim --> FluidBuffers
    RippleSim --> FluidBuffers_DoubleFbo
    RippleSim --> FluidBuffers_Formats
    FluidBuffers ..> FluidBuffers_Formats
    FluidSceneBase ..> FluidParticles
    FluidSim ..> FluidSim_Splat
    RippleSim ..> FluidBuffers_Fbo
    pkg_scenes_dev_geode_render ..> FlowField
    pkg_scenes_dev_geode_render ..> RippleSim
    FluidQuality ..> pkg_app_dev_geode_billing
    FluidSceneBase ..> pkg_scenes_dev_geode_analysis
    FluidSim ..> pkg_scenes_dev_geode_render_scene : 2
    pkg_scenes_dev_geode_render_offscreen ..> FlowField
    pkg_scenes_dev_geode_render_offscreen ..> FluidScene
    pkg_scenes_dev_geode_render_offscreen ..> RippleSim
    pkg_scenes_dev_geode_render_scene ..> FluidBuffers : 3
    pkg_scenes_dev_geode_render_scene ..> FluidBuffers_DoubleFbo : 3
    pkg_scenes_dev_geode_render_scene ..> FluidBuffers_Formats : 2
    CurlFlowScene ..> pkg_scenes_dev_geode_analysis
    FluidEmitters ..> pkg_scenes_dev_geode_render_scene
    FluidScene ..> pkg_scenes_dev_geode_analysis
    FluidSceneBase ..> pkg_scenes_dev_geode_render_scene
    WaterScene ..> pkg_scenes_dev_geode_analysis
```

## engine:scenes :: dev.geode.render.compute

```mermaid
classDiagram
    direction TB

    namespace scenes.dev.geode.render.compute {
        class BaseSimPass["BaseSimPass"] {
            <<abstract class>>
        }
        class ComputeSimPass["ComputeSimPass"] {
            <<class>>
        }
        class FragmentSimPass["FragmentSimPass"] {
            <<class>>
        }
        class SimBuild["SimBuild"] {
            <<sealed interface>>
        }
        class SimField["SimField"] {
            <<class>>
        }
        class SimGlsl["SimGlsl"] {
            <<object>>
        }
        class SimPass["SimPass"] {
            <<interface>>
        }
        class SimSampling["SimSampling"] {
            <<enumeration>>
        }
        class SimSpec["SimSpec"] {
            <<class>>
        }
        class SimStateEncoding["SimStateEncoding"] {
            <<data class>>
        }
        class SimUniformBinder["SimUniformBinder"] {
            <<fun interface>>
        }
        class SimUniforms["SimUniforms"] {
            <<class>>
        }
        class SimBuild_Failed["SimBuild.Failed"] {
            <<data class>>
        }
        class SimBuild_Ready["SimBuild.Ready"] {
            <<data class>>
        }
        class SimField_Side["SimField.Side"] {
            <<class>>
        }
    }

    namespace other_packages {
        class pkg_scenes_dev_geode_render_scene["~.render.scene"] {
            49 types
        }
        class pkg_gl_dev_geode_engine_gl["~.engine.gl"] {
            53 types
        }
    }

    SimPass <|.. BaseSimPass
    BaseSimPass <|-- ComputeSimPass
    BaseSimPass <|-- FragmentSimPass
    SimBuild <|.. SimBuild_Failed
    SimBuild <|.. SimBuild_Ready
    BaseSimPass --> SimField
    BaseSimPass --> SimStateEncoding
    ComputeSimPass --> SimField
    ComputeSimPass --> SimStateEncoding
    FragmentSimPass --> SimField
    FragmentSimPass --> SimStateEncoding
    SimBuild --> SimPass
    SimBuild_Ready --> SimPass
    SimSpec --> SimSampling
    BaseSimPass ..> SimGlsl
    ComputeSimPass ..> SimUniformBinder
    FragmentSimPass ..> SimUniformBinder
    SimPass ..> SimUniformBinder
    SimUniformBinder ..> SimUniforms
    ComputeSimPass ..> pkg_gl_dev_geode_engine_gl : 2
    SimField ..> pkg_gl_dev_geode_engine_gl
    SimSpec ..> pkg_gl_dev_geode_engine_gl : 2
    SimStateEncoding ..> pkg_gl_dev_geode_engine_gl
    pkg_scenes_dev_geode_render_scene ..> SimPass
    pkg_scenes_dev_geode_render_scene ..> SimUniforms
```

## engine:scenes :: dev.geode.render.offscreen

```mermaid
classDiagram
    direction TB

    namespace scenes.dev.geode.render.offscreen {
        class OffscreenCompositor["OffscreenCompositor"] {
            <<class>>
        }
        class OffscreenGradeState["OffscreenGradeState"] {
            <<class>>
        }
        class OffscreenGradeUniforms["OffscreenGradeUniforms"] {
            <<data class>>
        }
        class OffscreenRenderSpec["OffscreenRenderSpec"] {
            <<data class>>
        }
        class OffscreenSceneRenderer["OffscreenSceneRenderer"] {
            <<class>>
        }
        class OffscreenSceneRenderer_EffectUse["OffscreenSceneRenderer.EffectUse"] {
            <<data class>>
        }
    }

    namespace other_packages {
        class pkg_scenes_dev_geode_render["~.render"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_scene["~.render.scene"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_fluid["~.render.fluid"] {
            34 types
        }
        class pkg_scenes_dev_geode_analysis["~.analysis"] {
            22 types
        }
    }

    OffscreenSceneRenderer --> OffscreenCompositor
    OffscreenSceneRenderer --> OffscreenRenderSpec
    OffscreenSceneRenderer ..> OffscreenSceneRenderer_EffectUse
    OffscreenRenderSpec ..> pkg_scenes_dev_geode_render : 2
    OffscreenRenderSpec ..> pkg_scenes_dev_geode_render_scene
    OffscreenSceneRenderer ..> pkg_scenes_dev_geode_analysis
    OffscreenSceneRenderer ..> pkg_scenes_dev_geode_render
    OffscreenSceneRenderer ..> pkg_scenes_dev_geode_render_fluid : 3
    OffscreenSceneRenderer ..> pkg_scenes_dev_geode_render_scene
```

## engine:scenes :: dev.geode.analysis

```mermaid
classDiagram
    direction TB

    namespace scenes.dev.geode.analysis {
        class AnalysisCache["AnalysisCache"] {
            <<object>>
        }
        class AnalysisEngine["AnalysisEngine"] {
            <<class>>
        }
        class AnalysisIdentity["AnalysisIdentity"] {
            <<object>>
        }
        class ArtPalette["ArtPalette"] {
            <<object>>
        }
        class AudioFeatures["AudioFeatures"] {
            <<data class>>
        }
        class BarTrim["BarTrim"] {
            <<object>>
        }
        class BeatTuning["BeatTuning"] {
            <<object>>
        }
        class FeatureRingBridge["FeatureRingBridge"] {
            <<class>>
        }
        class FeatureTimeline["FeatureTimeline"] {
            <<class>>
        }
        class FrameAccumulator["FrameAccumulator"] {
            <<class>>
        }
        class IntelligenceMode["IntelligenceMode"] {
            <<enumeration>>
        }
        class KeyPalette["KeyPalette"] {
            <<object>>
        }
        class LiveInputProfile["LiveInputProfile"] {
            <<enumeration>>
        }
        class OfflineAnalyzer["OfflineAnalyzer"] {
            <<class>>
        }
        class PlaybackMath["PlaybackMath"] {
            <<object>>
        }
        class SceneSuggester["SceneSuggester"] {
            <<object>>
        }
        class SearchMatcher["SearchMatcher"] {
            <<object>>
        }
        class TimelineFrame["TimelineFrame"] {
            <<data class>>
        }
        class AnalysisEngine_Pass["AnalysisEngine.Pass"] {
            <<inner class>>
        }
        class ArtPalette_Extracted["ArtPalette.Extracted"] {
            <<data class>>
        }
        class OfflineAnalyzer_StreamingPipeline["OfflineAnalyzer.StreamingPipeline"] {
            <<class>>
        }
        class SceneSuggester_Affinity["SceneSuggester.Affinity"] {
            <<data class>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_ui["~.ui"] {
            117 types
        }
        class pkg_app_dev_geode_export["~.export"] {
            109 types
        }
        class pkg_app_dev_geode_audio["~.audio"] {
            21 types
        }
        class pkg_app_dev_geode_wallpaper["~.wallpaper"] {
            4 types
        }
        class pkg_scenes_dev_geode_render["~.render"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_scene["~.render.scene"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_fluid["~.render.fluid"] {
            34 types
        }
        class pkg_scenes_dev_geode_render_offscreen["~.render.offscreen"] {
            6 types
        }
        class pkg_audio_core_dev_geode_engine_audio["~.engine.audio"] {
            54 types
        }
    }

    AnalysisEngine --> AudioFeatures
    FeatureTimeline --> TimelineFrame
    SceneSuggester --> SceneSuggester_Affinity
    TimelineFrame --> AudioFeatures
    ArtPalette ..> ArtPalette_Extracted
    FeatureRingBridge ..> AudioFeatures
    FrameAccumulator ..> TimelineFrame
    OfflineAnalyzer_StreamingPipeline ..> AudioFeatures
    OfflineAnalyzer_StreamingPipeline ..> FeatureTimeline
    SceneSuggester ..> FeatureTimeline
    AnalysisEngine ..> pkg_audio_core_dev_geode_engine_audio
    pkg_app_dev_geode_audio ..> AudioFeatures
    pkg_app_dev_geode_export ..> FeatureTimeline : 2
    pkg_scenes_dev_geode_render ..> AudioFeatures : 3
    pkg_scenes_dev_geode_render_fluid ..> AudioFeatures : 4
    pkg_scenes_dev_geode_render_offscreen ..> FeatureTimeline
    pkg_scenes_dev_geode_render_scene ..> AudioFeatures : 5
    pkg_app_dev_geode_ui ..> FeatureTimeline : 2
    pkg_app_dev_geode_ui ..> BeatTuning
    pkg_app_dev_geode_ui ..> AnalysisEngine : 2
    pkg_app_dev_geode_ui ..> AudioFeatures : 3
    pkg_app_dev_geode_ui ..> IntelligenceMode : 2
    FeatureRingBridge ..> pkg_audio_core_dev_geode_engine_audio
    LiveInputProfile ..> pkg_scenes_dev_geode_render_scene
    pkg_app_dev_geode_ui ..> LiveInputProfile
    pkg_app_dev_geode_wallpaper ..> AudioFeatures
```

## engine:gl :: dev.geode.engine.gl

```mermaid
classDiagram
    direction TB

    namespace gl.dev.geode.engine.gl {
        class BaselineCause["BaselineCause"] {
            <<sealed interface>>
        }
        class CapabilityCache["CapabilityCache"] {
            <<object>>
        }
        class ComputeCompileException["ComputeCompileException"] {
            <<class>>
        }
        class ComputeLimits["ComputeLimits"] {
            <<data class>>
        }
        class ComputePass["ComputePass"] {
            <<class>>
        }
        class ComputeProgram["ComputeProgram"] {
            <<class>>
        }
        class ComputeProof["ComputeProof"] {
            <<data class>>
        }
        class ComputeReader["ComputeReader"] {
            <<enumeration>>
        }
        class ComputeSupport["ComputeSupport"] {
            <<sealed interface>>
        }
        class DeviceGl["DeviceGl"] {
            <<object>>
        }
        class EglProbeHarness["EglProbeHarness"] {
            <<object>>
        }
        class EglProbeOutcome["EglProbeOutcome"] {
            <<sealed interface>>
        }
        class EglStage["EglStage"] {
            <<enumeration>>
        }
        class FormatPlan["FormatPlan"] {
            <<data class>>
        }
        class FormatPolicy["FormatPolicy"] {
            <<object>>
        }
        class FormatProbe["FormatProbe"] {
            <<data class>>
        }
        class GlCapabilities["GlCapabilities"] {
            <<data class>>
        }
        class GlIdentity["GlIdentity"] {
            <<data class>>
        }
        class GlImageFormat["GlImageFormat"] {
            <<enumeration>>
        }
        class GlProbeReport["GlProbeReport"] {
            <<data class>>
        }
        class GlProber["GlProber"] {
            <<object>>
        }
        class GlProfile["GlProfile"] {
            <<data class>>
        }
        class GlTier["GlTier"] {
            <<sealed interface>>
        }
        class GlVersion["GlVersion"] {
            <<data class>>
        }
        class ImageAccess["ImageAccess"] {
            <<enumeration>>
        }
        class NoCompute["NoCompute"] {
            <<sealed interface>>
        }
        class ProbeSource["ProbeSource"] {
            <<enumeration>>
        }
        class ProbedFormat["ProbedFormat"] {
            <<enumeration>>
        }
        class ResolvedFormat["ResolvedFormat"] {
            <<data class>>
        }
        class TexelEncoding["TexelEncoding"] {
            <<enumeration>>
        }
        class TimerQuerySupport["TimerQuerySupport"] {
            <<enumeration>>
        }
        class WorkGroupCount["WorkGroupCount"] {
            <<data class>>
        }
        class WorkGroupSize["WorkGroupSize"] {
            <<data class>>
        }
        class BaselineCause_BelowEs31["BaselineCause.BelowEs31"] {
            <<data class>>
        }
        class BaselineCause_ComputeLimitsBelowSpecFloor["BaselineCause.ComputeLimitsBelowSpecFloor"] {
            <<data class>>
        }
        class BaselineCause_NoImageLoadStore["BaselineCause.NoImageLoadStore"] {
            <<data class>>
        }
        class BaselineCause_NoProbeContext["BaselineCause.NoProbeContext"] {
            <<data class>>
        }
        class BaselineCause_VersionUnparseable["BaselineCause.VersionUnparseable"] {
            <<data class>>
        }
        class CapabilityCache_LineReader["CapabilityCache.LineReader"] {
            <<class>>
        }
        class ComputeSupport_Available["ComputeSupport.Available"] {
            <<data class>>
        }
        class ComputeSupport_Unavailable["ComputeSupport.Unavailable"] {
            <<data class>>
        }
        class EglProbeOutcome_Probed["EglProbeOutcome.Probed"] {
            <<data class>>
        }
        class EglProbeOutcome_Unavailable["EglProbeOutcome.Unavailable"] {
            <<data class>>
        }
        class GlProber_FormatSpec["GlProber.FormatSpec"] {
            <<class>>
        }
        class GlProber_GlArena["GlProber.GlArena"] {
            <<class>>
        }
        class GlProber_GlStateGuard["GlProber.GlStateGuard"] {
            <<class>>
        }
        class GlProber_ProbeTarget["GlProber.ProbeTarget"] {
            <<class>>
        }
        class GlTier_Baseline["GlTier.Baseline"] {
            <<data class>>
        }
        class GlTier_Compute["GlTier.Compute"] {
            <<data class>>
        }
        class NoCompute_DeviceIsBaseline["NoCompute.DeviceIsBaseline"] {
            <<data class>>
        }
        class NoCompute_GroupCountBelowSpecFloor["NoCompute.GroupCountBelowSpecFloor"] {
            <<data class>>
        }
        class NoCompute_GroupSizeBelowSpecFloor["NoCompute.GroupSizeBelowSpecFloor"] {
            <<data class>>
        }
        class NoCompute_LimitsUnreadable["NoCompute.LimitsUnreadable"] {
            <<data class>>
        }
    }

    namespace other_packages {
        class pkg_scenes_dev_geode_render["~.render"] {
            49 types
        }
        class pkg_scenes_dev_geode_render_compute["~.render.compute"] {
            15 types
        }
    }

    namespace kotlin {
        class ext_Comparable["Comparable"] {
            <<interface>>
        }
        class ext_RuntimeException["RuntimeException"] {
            <<class>>
        }
    }

    BaselineCause <|.. BaselineCause_BelowEs31
    BaselineCause <|.. BaselineCause_ComputeLimitsBelowSpecFloor
    BaselineCause <|.. BaselineCause_NoImageLoadStore
    BaselineCause <|.. BaselineCause_NoProbeContext
    BaselineCause <|.. BaselineCause_VersionUnparseable
    ComputeSupport <|.. ComputeSupport_Available
    ComputeSupport <|.. ComputeSupport_Unavailable
    EglProbeOutcome <|.. EglProbeOutcome_Probed
    EglProbeOutcome <|.. EglProbeOutcome_Unavailable
    GlTier <|.. GlTier_Baseline
    GlTier <|.. GlTier_Compute
    NoCompute <|.. NoCompute_DeviceIsBaseline
    NoCompute <|.. NoCompute_GroupCountBelowSpecFloor
    NoCompute <|.. NoCompute_GroupSizeBelowSpecFloor
    NoCompute <|.. NoCompute_LimitsUnreadable
    BaselineCause --> GlVersion
    BaselineCause_BelowEs31 --> GlVersion
    ComputeLimits --> WorkGroupCount
    ComputeLimits --> WorkGroupSize
    ComputePass --> ComputeProgram
    ComputePass --> ComputeReader
    ComputePass --> WorkGroupSize
    ComputeProof --> GlVersion
    ComputeSupport --> ComputeLimits
    ComputeSupport --> ComputeProof
    ComputeSupport --> NoCompute
    ComputeSupport_Available --> ComputeLimits
    ComputeSupport_Available --> ComputeProof
    ComputeSupport_Unavailable --> NoCompute
    DeviceGl --> GlProfile
    EglProbeOutcome --> EglStage
    EglProbeOutcome_Unavailable --> EglStage
    FormatPlan --> ResolvedFormat
    GlCapabilities --> GlVersion
    GlCapabilities --> TimerQuerySupport
    GlProbeReport --> ProbedFormat
    GlProber --> GlProber_FormatSpec
    GlProber --> ProbedFormat
    GlProfile --> FormatPlan
    GlProfile --> GlCapabilities
    GlProfile --> GlProbeReport
    GlProfile --> GlTier
    GlProfile --> ProbeSource
    GlTier --> BaselineCause
    GlTier --> ComputeProof
    GlTier_Baseline --> BaselineCause
    GlTier_Compute --> ComputeProof
    NoCompute --> BaselineCause
    NoCompute --> WorkGroupCount
    NoCompute --> WorkGroupSize
    NoCompute_DeviceIsBaseline --> BaselineCause
    NoCompute_GroupCountBelowSpecFloor --> WorkGroupCount
    NoCompute_GroupSizeBelowSpecFloor --> WorkGroupSize
    ResolvedFormat --> ProbedFormat
    ResolvedFormat --> TexelEncoding
    CapabilityCache ..> FormatProbe
    CapabilityCache ..> GlProbeReport
    ComputePass ..> WorkGroupCount
    DeviceGl ..> GlIdentity
    EglProbeHarness ..> EglProbeOutcome
    FormatPolicy ..> FormatPlan
    FormatPolicy ..> GlProbeReport
    FormatPolicy ..> ResolvedFormat
    GlProbeReport ..> FormatProbe
    GlProber ..> GlIdentity
    GlProber ..> GlProbeReport
    GlProber_GlArena ..> GlProber_FormatSpec
    GlProber_GlArena ..> GlProber_ProbeTarget
    pkg_scenes_dev_geode_render ..> GlProfile
    pkg_scenes_dev_geode_render_compute ..> ComputePass
    pkg_scenes_dev_geode_render_compute ..> WorkGroupCount
    pkg_scenes_dev_geode_render_compute ..> GlImageFormat : 2
    pkg_scenes_dev_geode_render_compute ..> ComputeReader
    pkg_scenes_dev_geode_render_compute ..> WorkGroupSize
    ext_RuntimeException <|-- ComputeCompileException
    ext_Comparable <|.. GlVersion
```

## engine:audio-core :: dev.geode.engine.audio

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.audio {
        class PcmRingBuffer["PcmRingBuffer"] {
            <<class>>
        }
    }

    namespace audio_core.dev.geode.engine.audio {
        class AdaptiveRange["AdaptiveRange"] {
            <<class>>
        }
        class AdaptiveWhitening["AdaptiveWhitening"] {
            <<class>>
        }
        class AnalysisBranch["AnalysisBranch"] {
            <<data class>>
        }
        class AudioPresentationClock["AudioPresentationClock"] {
            <<class>>
        }
        class BarTracker["BarTracker"] {
            <<class>>
        }
        class BeatGrid["BeatGrid"] {
            <<class>>
        }
        class Chromagram["Chromagram"] {
            <<class>>
        }
        class ClockSegment["ClockSegment"] {
            <<data class>>
        }
        class DrumChannels["DrumChannels"] {
            <<class>>
        }
        class Envelope["Envelope"] {
            <<class>>
        }
        class FeatureFrame["FeatureFrame"] {
            <<class>>
        }
        class FeatureRing["FeatureRing"] {
            <<class>>
        }
        class FrameGrid["FrameGrid"] {
            <<class>>
        }
        class FrameLevels["FrameLevels"] {
            <<object>>
        }
        class HarmonicBalance["HarmonicBalance"] {
            <<class>>
        }
        class InputPosition["InputPosition"] {
            <<sealed interface>>
        }
        class KeyDetector["KeyDetector"] {
            <<class>>
        }
        class LogBands["LogBands"] {
            <<class>>
        }
        class MelBank["MelBank"] {
            <<class>>
        }
        class Mfcc["Mfcc"] {
            <<class>>
        }
        class MidSideWindow["MidSideWindow"] {
            <<class>>
        }
        class OnsetPeakPicker["OnsetPeakPicker"] {
            <<class>>
        }
        class PcmSink["PcmSink"] {
            <<fun interface>>
        }
        class PresentationSnapshot["PresentationSnapshot"] {
            <<class>>
        }
        class PresentationTime["PresentationTime"] {
            <<sealed interface>>
        }
        class PulseReplay["PulseReplay"] {
            <<object>>
        }
        class ReactiveAnalyzer["ReactiveAnalyzer"] {
            <<class>>
        }
        class RingReadResult["RingReadResult"] {
            <<sealed interface>>
        }
        class RingReader["RingReader"] {
            <<class>>
        }
        class SampleRing["SampleRing"] {
            <<class>>
        }
        class SpectralContrast["SpectralContrast"] {
            <<class>>
        }
        class SpectralDescriptors["SpectralDescriptors"] {
            <<object>>
        }
        class SpectralFlux["SpectralFlux"] {
            <<class>>
        }
        class Spectrum["Spectrum"] {
            <<class>>
        }
        class StereoField["StereoField"] {
            <<object>>
        }
        class StructureTracker["StructureTracker"] {
            <<class>>
        }
        class SuperFlux["SuperFlux"] {
            <<class>>
        }
        class TempoStability["TempoStability"] {
            <<class>>
        }
        class TempoTracker["TempoTracker"] {
            <<class>>
        }
        class WindowShape["WindowShape"] {
            <<enumeration>>
        }
        class WindowTable["WindowTable"] {
            <<class>>
        }
        class FeatureRing_Acquire["FeatureRing.Acquire"] {
            <<enumeration>>
        }
        class InputPosition_At["InputPosition.At"] {
            <<data class>>
        }
        class InputPosition_Unknown["InputPosition.Unknown"] {
            <<data object>>
        }
        class PresentationTime_At["PresentationTime.At"] {
            <<data class>>
        }
        class PresentationTime_Skipped["PresentationTime.Skipped"] {
            <<data class>>
        }
        class PresentationTime_StaleEpoch["PresentationTime.StaleEpoch"] {
            <<data class>>
        }
        class PresentationTime_Unknown["PresentationTime.Unknown"] {
            <<data object>>
        }
        class PulseReplay_Result["PulseReplay.Result"] {
            <<class>>
        }
        class RingReadResult_Discontinuity["RingReadResult.Discontinuity"] {
            <<data class>>
        }
        class RingReadResult_Gap["RingReadResult.Gap"] {
            <<data class>>
        }
        class RingReadResult_NotYetAvailable["RingReadResult.NotYetAvailable"] {
            <<data object>>
        }
        class RingReadResult_Ok["RingReadResult.Ok"] {
            <<data class>>
        }
        class StereoField_Reading["StereoField.Reading"] {
            <<data class>>
        }
    }

    namespace other_packages {
        class pkg_scenes_dev_geode_analysis["~.analysis"] {
            22 types
        }
        class pkg_audio_android_dev_geode_engine_audioandroid["~.engine.audioandroid"] {
            9 types
        }
    }

    PcmSink <|.. PcmRingBuffer
    InputPosition <|.. InputPosition_At
    InputPosition <|.. InputPosition_Unknown
    PresentationTime <|.. PresentationTime_At
    PresentationTime <|.. PresentationTime_Skipped
    PresentationTime <|.. PresentationTime_StaleEpoch
    PresentationTime <|.. PresentationTime_Unknown
    RingReadResult <|.. RingReadResult_Discontinuity
    RingReadResult <|.. RingReadResult_Gap
    RingReadResult <|.. RingReadResult_NotYetAvailable
    RingReadResult <|.. RingReadResult_Ok
    PcmSink <|.. SampleRing
    AudioPresentationClock --> PresentationSnapshot
    DrumChannels --> LogBands
    FrameGrid --> AnalysisBranch
    MidSideWindow --> SampleRing
    PresentationSnapshot --> ClockSegment
    RingReader --> SampleRing
    WindowTable --> WindowShape
    AudioPresentationClock ..> ClockSegment
    PresentationSnapshot ..> InputPosition
    RingReader ..> RingReadResult
    pkg_scenes_dev_geode_analysis ..> SampleRing
    pkg_audio_android_dev_geode_engine_audioandroid ..> PcmSink
    pkg_audio_android_dev_geode_engine_audioandroid ..> AudioPresentationClock
    pkg_scenes_dev_geode_analysis ..> FeatureFrame
```

## engine:audio-android :: dev.geode.engine.audioandroid

```mermaid
classDiagram
    direction TB

    namespace app.dev.geode.audio.dsp {
        class MvzAudioProcessorChain["MvzAudioProcessorChain"] {
            <<class>>
        }
    }

    namespace audio_android.dev.geode.engine.audioandroid {
        class PcmSampleWidth["PcmSampleWidth"] {
            <<enumeration>>
        }
        class PcmTap["PcmTap"] {
            <<class>>
        }
        class PcmTapFormat["PcmTapFormat"] {
            <<data class>>
        }
        class SinkClockDiagnostics["SinkClockDiagnostics"] {
            <<data class>>
        }
        class SinkClockDriver["SinkClockDriver"] {
            <<class>>
        }
        class SinkClockHooks["SinkClockHooks"] {
            <<interface>>
        }
        class SkippedFrameSource["SkippedFrameSource"] {
            <<fun interface>>
        }
        class TapBoundaryListener["TapBoundaryListener"] {
            <<fun interface>>
        }
        class SinkClockHooks_None["SinkClockHooks.None"] {
            <<data object>>
        }
    }

    namespace other_packages {
        class pkg_app_dev_geode_audio["~.audio"] {
            21 types
        }
        class pkg_app_dev_geode_audio_dsp["~.audio.dsp"] {
            1 types
        }
        class pkg_audio_core_dev_geode_engine_audio["~.engine.audio"] {
            54 types
        }
    }

    namespace androidx.media3.exoplayer {
        class ext_AudioBufferSink["TeeAudioProcessor.AudioBufferSink"] {
            <<interface>>
        }
    }

    SkippedFrameSource <|.. MvzAudioProcessorChain
    SinkClockHooks <|.. SinkClockDriver
    TapBoundaryListener <|.. SinkClockDriver
    SinkClockHooks <|.. SinkClockHooks_None
    PcmTap --> PcmTapFormat
    PcmTap --> TapBoundaryListener
    PcmTapFormat --> PcmSampleWidth
    SinkClockDriver --> SinkClockDiagnostics
    SinkClockDriver --> SkippedFrameSource
    SinkClockHooks ..> SkippedFrameSource
    SinkClockHooks_None ..> SkippedFrameSource
    pkg_app_dev_geode_audio ..> SinkClockHooks
    pkg_app_dev_geode_audio ..> SinkClockHooks_None
    pkg_app_dev_geode_audio_dsp ..> SinkClockHooks
    pkg_app_dev_geode_audio_dsp ..> SinkClockHooks_None
    PcmTap ..> pkg_audio_core_dev_geode_engine_audio
    SinkClockDriver ..> pkg_audio_core_dev_geode_engine_audio
    ext_AudioBufferSink <|.. PcmTap
```

## engine:runtime :: dev.geode.engine.runtime

```mermaid
classDiagram
    direction TB

    namespace runtime.dev.geode.engine.runtime {
        class EngineComposition["EngineComposition"] {
            <<class>>
        }
        class EngineLifetime["EngineLifetime"] {
            <<interface>>
        }
        class LifetimeId["LifetimeId"] {
            <<enumeration>>
        }
        class LifetimePhase["LifetimePhase"] {
            <<enumeration>>
        }
        class ManagedLifetime["ManagedLifetime"] {
            <<abstract class>>
        }
    }

    EngineLifetime <|.. ManagedLifetime
    EngineLifetime --> LifetimeId
    EngineLifetime --> LifetimePhase
    ManagedLifetime --> LifetimeId
    ManagedLifetime --> LifetimePhase
    EngineComposition ..> LifetimeId
```

## build-logic :: (build-logic)

```mermaid
classDiagram
    direction TB

    namespace build_logic {
        class ProvenanceRules["ProvenanceRules"] {
            <<object>>
        }
        class ProvenanceSourceRecord["ProvenanceSourceRecord"] {
            <<data class>>
        }
        class ProvenanceViolation["ProvenanceViolation"] {
            <<sealed interface>>
        }
        class ScannedFile["ScannedFile"] {
            <<data class>>
        }
        class ProvenanceViolation_ForbiddenSourceMentioned["ProvenanceViolation.ForbiddenSourceMentioned"] {
            <<data class>>
        }
        class ProvenanceViolation_ForbiddenTier["ProvenanceViolation.ForbiddenTier"] {
            <<data class>>
        }
        class ProvenanceViolation_LicenceMismatch["ProvenanceViolation.LicenceMismatch"] {
            <<data class>>
        }
        class ProvenanceViolation_MissingNotice["ProvenanceViolation.MissingNotice"] {
            <<data class>>
        }
        class ProvenanceViolation_OriginCommitMismatch["ProvenanceViolation.OriginCommitMismatch"] {
            <<data class>>
        }
        class ProvenanceViolation_OriginWithoutSpdx["ProvenanceViolation.OriginWithoutSpdx"] {
            <<data class>>
        }
        class ProvenanceViolation_UnknownOrigin["ProvenanceViolation.UnknownOrigin"] {
            <<data class>>
        }
    }

    ProvenanceViolation <|.. ProvenanceViolation_ForbiddenSourceMentioned
    ProvenanceViolation <|.. ProvenanceViolation_ForbiddenTier
    ProvenanceViolation <|.. ProvenanceViolation_LicenceMismatch
    ProvenanceViolation <|.. ProvenanceViolation_MissingNotice
    ProvenanceViolation <|.. ProvenanceViolation_OriginCommitMismatch
    ProvenanceViolation <|.. ProvenanceViolation_OriginWithoutSpdx
    ProvenanceViolation <|.. ProvenanceViolation_UnknownOrigin
```
