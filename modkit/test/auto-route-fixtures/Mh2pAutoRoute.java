package de.audi.app.terminalmode.smartphone.androidauto2.nav;
/** Test-only intent bridge; never packaged. */
public class Mh2pAutoRoute {
    public static boolean admitted;
    public static String last;
    public static boolean active(Object i) { return true; }
    public static boolean canPrepare(Object i) { return admitted; }
    public static void preparePending(Object i) { last="pending"; }
    public static void command(Object i,String c) { last=c; }
    public static void log(Object i,String text) { }
}
