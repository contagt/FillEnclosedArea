package org.openstreetmap.josm.plugins.fillenclosedarea;

import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

/**
 * Entry point for the "Fill Enclosed Area" JOSM plugin.
 *
 * The plugin adds a single map mode (toolbar toggle button) that, on click,
 * looks for a face of the existing way/node network that fully encloses the
 * clicked point and turns that face's boundary into a new closed way built
 * from the existing nodes.
 *
 * It intentionally does NOT:
 *  - bridge gaps between unconnected ways/nodes,
 *  - snap to nearby geometry that isn't already connected,
 *  - guess/interpolate any new coordinates,
 *  - create a freehand/approximate outline.
 *
 * If the clicked point is not inside a genuinely closed boundary, nothing is
 * created and the user gets a short notification instead.
 */
public class FillEnclosedAreaPlugin extends Plugin {

    private FillEnclosedAreaAction mode;

    public FillEnclosedAreaPlugin(PluginInformation info) {
        super(info);
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            if (mode == null) {
                mode = new FillEnclosedAreaAction();
            }
            IconToggleButton button = new IconToggleButton(mode);
            newFrame.addMapMode(button);
        }
    }
}
