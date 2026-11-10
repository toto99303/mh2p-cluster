package de.audi.tghu.navi.app.map;
/** Host fixture; no Audi implementation is executed by tests. */
public class AbstractMap {
    protected volatile boolean mapDrawn;
    private boolean initialized;
    private Object core;
    public Object getMVRequest() { return core; }
    public void setCore(Object value) { core = value; }
    public boolean isInitialized() { return initialized; }
    public void set(boolean init, boolean drawn) { initialized = init; mapDrawn = drawn; }
}
