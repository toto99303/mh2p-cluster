package de.audi.tghu.navi.app.map.dsi;
public class MVRequestControl {
    public StringBuffer calls = new StringBuffer();
    public MVResponseControl response = new MVResponseControl();
    public boolean ready = true;
    public boolean missingDiagnosticResponse;
    private final int id;
    public MVRequestControl(int id) { this.id = id; }
    public int getInstanceID() { return id; }
    public boolean isReady() { return ready; }
    public MVResponseControl getMVResponseControl() { return missingDiagnosticResponse ? null : response; }
    public void suspendMapViewer() { calls.append("suspend;"); response.currentState = "SUSPENDING"; }
    public void wakeupMapViewer() { calls.append("wake;"); response.currentState = "WAKING_UP"; }
    public void viewSetVisible(boolean value) { calls.append(value ? "visible;" : "hidden;"); response.visible = value; }
    public void viewFreeze(boolean value) { calls.append(value ? "freeze;" : "unfreeze;"); response.frozen = value; }
}
