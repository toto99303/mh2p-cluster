package de.eso.hmiservice.display;
/** Synthetic cache shape; no OEM implementation is executed. */
public class DisplayManager {
    private boolean[] lastRequestAnswered={false,false};
    private static Context[] confirmedActiveContext=new Context[2], requestedContexts=new Context[2];
    public static class Context {
        private int[] list;
        Context(int[] a) { list=a; }
        public int[] getDisplayableList() { return list; }
    }
    public synchronized void fixture(boolean answered,int[] active,int[] requested) {
        lastRequestAnswered[1]=answered;
        confirmedActiveContext[1]=active==null?null:new Context(active);
        requestedContexts[1]=requested==null?null:new Context(requested);
    }
}
