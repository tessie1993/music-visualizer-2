package dev.musicviz.render.scene

/**
 * Builds fresh [Scene] instances for one GL context.
 *
 * GL handles are not shareable across contexts, so the offline export context
 * cannot reuse the live renderer's scene objects — it asks for its own through
 * this factory. The renderer supplies the implementation because it is what
 * knows how to build each scene (shader sources, textures, injection
 * programs); the export path only calls [create].
 *
 * Lives in `render.scene` next to [Scene], not in the export package: its one
 * method returns a render type and has nothing export-specific about it, and
 * naming it there is what forced the renderer to import the exporter.
 */
interface SceneFactory {
    fun create(): Scene
}
