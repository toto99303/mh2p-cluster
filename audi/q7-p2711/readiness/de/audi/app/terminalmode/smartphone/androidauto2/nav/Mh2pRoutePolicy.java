/* Licensed under CC BY-NC-SA 4.0, matching this repository. */
package de.audi.app.terminalmode.smartphone.androidauto2.nav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringTokenizer;

/** Pure parsing/selection policy for the verified ER P2711 profile. */
public final class Mh2pRoutePolicy {
    public static final int DISPLAY = 4;
    public static final int NATIVE = 33;
    public static final int AA = 62;

    public static final class State {
        public int[] actual;
        public int[] oem;
        public boolean clusterGeometry;
        public boolean nativeSource;
        public boolean aaSource;
        public boolean aaRegistered;
    }

    public static State parse(String gs, String gd, int[] oem) {
        State s = new State();
        s.oem = copy(oem);
        boolean cluster = false, found = false, dimensions = false, terminal = false;
        ArrayList layers = new ArrayList();
        StringTokenizer lines = new StringTokenizer(gs, "\r\n");
        while (lines.hasMoreTokens()) {
            String line = lines.nextToken().trim();
            if (line.startsWith("display: ")) {
                cluster = "display: Cluster Display".equals(line);
                if (cluster) {
                    if (found) throw new IllegalArgumentException("duplicate cockpit display");
                    found = true;
                }
            } else if (cluster && line.startsWith("size: ")) {
                dimensions = "size: 1920 x 722".equals(line);
            } else if (cluster && line.startsWith("terminal: ")) {
                terminal = "terminal: LVDS2".equals(line);
            } else if (cluster && line.length() > 0 && Character.isDigit(line.charAt(0))) {
                int end = line.indexOf(' ');
                if (end < 0) throw new IllegalArgumentException("invalid cockpit layer");
                int id = Integer.parseInt(line.substring(0, end));
                if (id < 0 || id > 127 || layers.size() >= 8) throw new IllegalArgumentException("invalid layer range");
                layers.add(new Integer(id));
            }
        }
        if (!found) throw new IllegalArgumentException("cockpit display missing");
        s.actual = new int[layers.size()];
        for (int i = 0; i < s.actual.length; i++) s.actual[i] = ((Integer) layers.get(i)).intValue();
        s.clusterGeometry = dimensions && terminal;
        HashMap sources = new HashMap();
        HashMap registered = new HashMap();
        lines = new StringTokenizer(gd, "\r\n");
        int expectedCount = -1;
        while (lines.hasMoreTokens()) {
            String line = lines.nextToken().trim();
            if (line.startsWith("displaymanager knows ") && line.endsWith(" displayables:"))
                expectedCount = Integer.parseInt(line.substring(21, line.length()-14));
            StringTokenizer words = new StringTokenizer(line);
            if (words.countTokens() < 2) continue;
            String idword = words.nextToken(), type = words.nextToken();
            int id;
            try { id = Integer.parseInt(idword); } catch (NumberFormatException heading) { continue; }
            if (registered.put(new Integer(id), Boolean.TRUE) != null)
                throw new IllegalArgumentException("duplicate source ID");
            if (id == AA) s.aaRegistered = true; // Any source type occupies the slot.
            if (!"Software".equals(type)) continue;
            if (words.countTokens() < 3) throw new IllegalArgumentException("missing source dimensions");
            int width = Integer.parseInt(words.nextToken());
            if (!"x".equals(words.nextToken())) throw new IllegalArgumentException("invalid source dimensions");
            int height = Integer.parseInt(words.nextToken());
            if (width <= 0 || height <= 0 || sources.put(new Integer(id), new int[] {width, height}) != null)
                throw new IllegalArgumentException("invalid/duplicate source");
        }
        if (expectedCount < 0 || expectedCount != registered.size())
            throw new IllegalArgumentException("source query missing or incomplete");
        int[] nativeSize = (int[]) sources.get(new Integer(NATIVE));
        int[] aaSize = (int[]) sources.get(new Integer(AA));
        s.nativeSource = equal(nativeSize, new int[] {1920, 600});
        s.aaSource = equal(aaSize, new int[] {1920, 722});
        return s;
    }

    /** Preserve the current ordered HMI layers, replacing only native33.
     * The first automatic candidate admits the verified [41,33] family,
     * including the stock KDK20 variant. Other policies retain Audi output.
     */
    public static int[] aaLayers(int[] oem) {
        if (oem == null || oem.length < 2 || oem.length > 3) return null;
        int nativeCount = 0, hmiCount = 0, kdkCount = 0;
        int[] out = copy(oem);
        for (int i = 0; i < out.length; i++) {
            if (out[i] == NATIVE) { nativeCount++; out[i] = AA; }
            else if (out[i] == 41) hmiCount++;
            else if (out[i] == 20) kdkCount++;
            else return null;
        }
        return nativeCount == 1 && hmiCount == 1 && kdkCount <= 1 ? out : null;
    }

    public static boolean contains(int[] list, int value) {
        if (list != null) for (int i = 0; i < list.length; i++) if (list[i] == value) return true;
        return false;
    }
    public static boolean equal(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
    public static int[] copy(int[] a) {
        if (a == null) return null;
        int[] b = new int[a.length]; System.arraycopy(a, 0, b, 0, a.length); return b;
    }
    public static String list(int[] a) {
        if (a == null) return "unavailable";
        StringBuffer b = new StringBuffer("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(','); b.append(a[i]); }
        return b.append(']').toString();
    }
}
