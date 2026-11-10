package de.audi.tghu.navi.app.map.dsi;
/** Host fixture modeling only the inspected P2711 acknowledgement contract. */
public class MVResponseControl {
    String currentState = "ACTIVE";
    boolean visible = true;
    boolean frozen;
    public boolean isViewVisible() { return visible; }
    public boolean isViewFreeze() { return frozen; }
    public int getMapViewerRunLevel() { return "ACTIVE".equals(currentState) ? 4 : 1; }
    public String state() { return currentState; }
    public void ackWake() { if ("WAKING_UP".equals(currentState)) currentState = "ACTIVE"; }
    public void ackSuspend() { if ("SUSPENDING".equals(currentState)) currentState = "INACTIVE"; }
}
