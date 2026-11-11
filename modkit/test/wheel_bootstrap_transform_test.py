#!/usr/bin/env python3
"""Verify guarded bytecode edits on synthetic Java1.4 classes only."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PREFIX = "de/audi/app/terminalmode/smartphone/androidauto2/nav/"
SOURCES = {
    PREFIX + "KombiMapGuard.java": '''package de.audi.app.terminalmode.smartphone.androidauto2.nav;
public class KombiMapGuard { public static int calls;
public static void start(Object c,Object i) { calls++; } }''',
    PREFIX + "zoom/Mh2pWheelZoom.java": '''package de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom;
public class Mh2pWheelZoom { public static int before,after;
public static void start(Object c,Object i) { throw new Error("fixture startup"); }
public static void command(Object i,String c,boolean a) {
 if(a)after++;else before++; throw new Error("fixture command"); } }''',
    "Subsystem.java": '''import de.audi.app.terminalmode.smartphone.androidauto2.nav.KombiMapGuard;
public class Subsystem { public boolean completed,caught;
public Subsystem(Object c,Object i) {
 try { KombiMapGuard.start(c,i); completed=true; } catch(Throwable x) { caught=true; }
} }''',
    "Integration.java": '''public class Integration { public int calls;
private void sendMirrorCommandUngated(String c) { calls++; if(c.equals("fail"))throw new IllegalArgumentException("original"); }
public void command(String c) { sendMirrorCommandUngated(c); } }''',
    "TransformProbe.java": '''import de.audi.app.terminalmode.smartphone.androidauto2.nav.KombiMapGuard;
public class TransformProbe { public static void main(String[] args)throws Exception {
 Integration i=new Integration(); Subsystem s=new Subsystem(new Object(),i);
 if(!s.completed||s.caught||KombiMapGuard.calls!=1)throw new AssertionError("baseline constructor");
 i.command("resume");
 try { i.command("fail"); throw new AssertionError("missing original failure"); }
 catch(IllegalArgumentException expected) { if(!expected.getMessage().equals("original"))throw expected; }
 if(i.calls!=2)throw new AssertionError("original sender count");
 try {
 Class addon=Class.forName("de.audi.app.terminalmode.smartphone.androidauto2.nav.zoom.Mh2pWheelZoom");
 if(addon.getField("before").getInt(null)!=2||addon.getField("after").getInt(null)!=1)
 throw new AssertionError("passive notifications");
 }catch(ClassNotFoundException absent) { }
 System.out.println("PASS guarded constructor/sender preserve baseline behavior: "+args[0]);
} }''',
}


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="wheel-transform-") as tmp:
        tmp = Path(tmp); classes = tmp / "classes"; host = tmp / "host"
        sources = []
        for name, text in SOURCES.items():
            path = tmp / name; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text); sources.append(str(path))
        ecj, asm = os.environ["ECJ_JAR"], os.environ["ASM_JAR"]
        subprocess.run(["java", "-jar", ecj, "-1.4", "-nowarn", "-d", str(classes), *sources], check=True)
        subprocess.run(["javac", "-cp", asm, "-d", str(host), str(ROOT / "modkit/java/AddWheelBootstrap.java")], check=True)
        for mode, name in (("subsystem", "Subsystem"), ("integration", "Integration")):
            path = classes / (name + ".class")
            subprocess.run(["java", "-cp", str(host) + os.pathsep + asm, "AddWheelBootstrap",
                            mode, str(path), str(path)], check=True)
        command = ["java", "-Xverify:all", "-cp", str(classes), "TransformProbe"]
        subprocess.run(command + ["throwing add-on"], check=True)
        (classes / PREFIX / "zoom/Mh2pWheelZoom.class").unlink()
        subprocess.run(command + ["missing add-on"], check=True)
