package de.audi.tghu.navi.app.util;
/** Synthetic host fixture only. */
public class AbstractNaviServiceWrapper {
    private final Object value;
    public AbstractNaviServiceWrapper(Object value) { this.value = value; }
    protected Object get() { return value; }
}
