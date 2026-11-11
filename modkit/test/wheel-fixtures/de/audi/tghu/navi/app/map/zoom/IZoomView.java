package de.audi.tghu.navi.app.map.zoom;
/** Synthetic host fixture only; never package as an OEM class. */
public interface IZoomView {
    interface IZoomViewListener {
        void onZoomLevelChanged(int steps);
        void onGestureStarted();
        void onGestureStopped();
        void onZoomEvent();
    }
}
