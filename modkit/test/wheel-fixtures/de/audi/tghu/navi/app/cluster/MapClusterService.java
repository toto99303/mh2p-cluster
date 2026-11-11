package de.audi.tghu.navi.app.cluster;
import de.audi.tghu.navi.app.map.zoom.IZoomView;
import java.util.HashSet;
/** Synthetic host fixture only; never package as an OEM class. */
public class MapClusterService {
    public final View view = new View();
    public View getRemoteZoomView() { return view; }
    public static class View {
        public final HashSet listeners = new HashSet();
        public void addListener(IZoomView.IZoomViewListener listener) { listeners.add(listener); }
        public void fire(int steps) {
            Object[] snapshot = listeners.toArray();
            for (int i = 0; i < snapshot.length; i++) {
                IZoomView.IZoomViewListener l = (IZoomView.IZoomViewListener) snapshot[i];
                l.onZoomEvent(); l.onZoomLevelChanged(steps);
            }
        }
    }
}
